/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE file at the root of the source
 * tree and available online at
 *
 * https://github.com/keeps/roda
 */
package org.roda.core.plugins.orchestrate;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.IOUtils;
import org.roda.core.RodaCoreFactory;
import org.roda.core.common.iterables.CloseableIterable;
import org.roda.core.data.adapter.filter.Filter;
import org.roda.core.data.adapter.sort.Sorter;
import org.roda.core.data.adapter.sublist.Sublist;
import org.roda.core.data.exceptions.InvalidParameterException;
import org.roda.core.data.v2.index.IndexResult;
import org.roda.core.data.v2.index.IsIndexed;
import org.roda.core.data.v2.ip.AIP;
import org.roda.core.data.v2.ip.File;
import org.roda.core.data.v2.ip.Representation;
import org.roda.core.data.v2.ip.TransferredResource;
import org.roda.core.data.v2.jobs.Job;
import org.roda.core.data.v2.jobs.Report;
import org.roda.core.index.IndexService;
import org.roda.core.model.ModelService;
import org.roda.core.plugins.Plugin;
import org.roda.core.plugins.PluginException;
import org.roda.core.plugins.PluginOrchestrator;
import org.roda.core.plugins.orchestrate.akka.AkkaJobWorkerActor;
import org.roda.core.plugins.orchestrate.akka.AkkaWorkerActor;
import org.roda.core.plugins.plugins.PluginHelper;
import org.roda.core.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Kill;
import akka.actor.Props;
import akka.dispatch.Futures;
import akka.dispatch.OnFailure;
import akka.dispatch.OnSuccess;
import akka.pattern.Patterns;
import akka.routing.Broadcast;
import akka.routing.RoundRobinPool;
import akka.util.Timeout;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

public class AkkaEmbeddedPluginOrchestrator implements PluginOrchestrator {
  private static final Logger LOGGER = LoggerFactory.getLogger(AkkaEmbeddedPluginOrchestrator.class);

  private static final int BLOCK_SIZE = 100;
  private static final Sorter SORTER = null;
  private static final int TIMEOUT = 1;
  private static final TimeUnit TIMEOUT_UNIT = TimeUnit.HOURS;
  private static final Timeout DEFAULT_TIMEOUT = new Timeout(Duration.create(TIMEOUT, TIMEOUT_UNIT));

  private final IndexService index;
  private final ModelService model;
  private final StorageService storage;

  private ActorSystem workersSystem;
  private ActorRef workersRouter;
  private ActorRef jobWorkersRouter;
  private int numberOfWorkers;

  // < jobId,
  private Map<String, Map<Plugin<?>, JobPluginInfo>> runningTasks;
  private Map<String, Integer> runningTasksTotalObjects;

  public AkkaEmbeddedPluginOrchestrator() {
    index = RodaCoreFactory.getIndexService();
    model = RodaCoreFactory.getModelService();
    storage = RodaCoreFactory.getStorageService();

    runningTasks = new HashMap<>();
    runningTasksTotalObjects = new HashMap<>();

    numberOfWorkers = RodaCoreFactory.getNumberOfPluginWorkers();
    workersSystem = ActorSystem.create("WorkersSystem");

    Props roundRobinPoolProps = new RoundRobinPool(numberOfWorkers)
      .props(Props.create(AkkaWorkerActor.class, storage, model, index));
    workersRouter = workersSystem.actorOf(roundRobinPoolProps, "WorkersRouter");

    Props roundRobinPoolProps2 = new RoundRobinPool(numberOfWorkers).props(Props.create(AkkaJobWorkerActor.class));
    jobWorkersRouter = workersSystem.actorOf(roundRobinPoolProps2, "JobWorkersRouter");
  }

  @Override
  public void setup() {
    // do nothing
  }

  @Override
  public void shutdown() {
    workersSystem.shutdown();
  }

  private <T extends Serializable> Plugin<T> getNewPluginInstanceAndRunBeforeExecute(Plugin<T> plugin,
    Class<T> pluginClass, List<Plugin<T>> innerPlugins, int numberOfObjects)
      throws InvalidParameterException, PluginException {
    Plugin<T> innerPlugin = RodaCoreFactory.getPluginManager().getPlugin(plugin.getClass().getCanonicalName(),
      pluginClass);
    innerPlugin.setParameterValues(plugin.getParameterValues());
    innerPlugins.add(innerPlugin);
    innerPlugin.beforeExecute(index, model, storage);

    String jobId = PluginHelper.getJobId(innerPlugin);
    JobPluginInfo jobPluginInfo = new JobPluginInfo();
    jobPluginInfo.setNumberOfObjects(numberOfObjects);
    jobPluginInfo.setStepsCompleted(0);
    synchronized (runningTasks) {
      if (runningTasks.get(jobId) != null) {
        runningTasks.get(jobId).put(innerPlugin, jobPluginInfo);
        runningTasksTotalObjects.put(jobId, runningTasksTotalObjects.get(jobId) + numberOfObjects);
      } else {
        Map<Plugin<?>, JobPluginInfo> inner = new HashMap<>();
        inner.put(innerPlugin, jobPluginInfo);
        runningTasks.put(jobId, inner);
        runningTasksTotalObjects.put(jobId, numberOfObjects);
      }
    }

    return innerPlugin;
  }

  @Override
  public <T extends IsIndexed> void runPluginFromIndex(Class<T> classToActOn, Filter filter, Plugin<T> plugin) {
    try {
      LOGGER.info("Started {}", plugin.getName());
      IndexResult<T> find;
      int offset = 0;
      int multiplier = 0;
      List<Future<Object>> futures = new ArrayList<>();
      List<Plugin<T>> innerPlugins = new ArrayList<>();
      Plugin<T> innerPlugin;
      do {
        // FIXME
        innerPlugin = getNewPluginInstanceAndRunBeforeExecute(plugin, classToActOn, innerPlugins, 0);
        // XXX block size could be recommended by plugin
        find = RodaCoreFactory.getIndexService().find(classToActOn, filter, SORTER, new Sublist(offset, BLOCK_SIZE));
        offset += find.getLimit();
        multiplier++;
        futures.add(Patterns.ask(workersRouter, new PluginMessage<T>(find.getResults(), innerPlugin), DEFAULT_TIMEOUT));

      } while (find.getTotalCount() > find.getOffset() + find.getLimit());

      final Future<Iterable<Object>> sequenceResult = Futures.sequence(futures, workersSystem.dispatcher());
      Await.result(sequenceResult, Duration.create(multiplier * TIMEOUT, TIMEOUT_UNIT));

      for (Plugin<T> p : innerPlugins) {
        p.afterExecute(index, model, storage);
      }

    } catch (Exception e) {
      LOGGER.error("Error running plugin from index", e);
    }
    LOGGER.info("Ended {}", plugin.getName());
  }

  @Override
  public List<Report> runPluginOnAIPs(Plugin<AIP> plugin, List<String> ids) {
    try {
      LOGGER.info("Started {}", plugin.getName());
      int multiplier = 0;
      Iterator<String> iter = ids.iterator();
      List<Future<Object>> futures = new ArrayList<>();
      List<Plugin<AIP>> innerPlugins = new ArrayList<>();
      Plugin<AIP> innerPlugin;
      String aipId;

      List<AIP> block = new ArrayList<AIP>();
      while (iter.hasNext()) {
        if (block.size() == BLOCK_SIZE) {
          innerPlugin = getNewPluginInstanceAndRunBeforeExecute(plugin, AIP.class, innerPlugins, BLOCK_SIZE);
          futures.add(Patterns.ask(workersRouter, new PluginMessage<AIP>(block, innerPlugin), DEFAULT_TIMEOUT));
          block = new ArrayList<AIP>();
          multiplier++;
        }

        aipId = iter.next();
        block.add(model.retrieveAIP(aipId));

      }

      if (!block.isEmpty()) {
        innerPlugin = getNewPluginInstanceAndRunBeforeExecute(plugin, AIP.class, innerPlugins, block.size());
        futures.add(Patterns.ask(workersRouter, new PluginMessage<AIP>(block, innerPlugin), DEFAULT_TIMEOUT));
        multiplier++;
      }

      final Future<Iterable<Object>> sequenceResult = Futures.sequence(futures, workersSystem.dispatcher());
      Iterable<Object> reports = Await.result(sequenceResult, Duration.create(multiplier * TIMEOUT, TIMEOUT_UNIT));

      for (Plugin<AIP> p : innerPlugins) {
        p.afterExecute(index, model, storage);
      }

      LOGGER.info("Ended {}", plugin.getName());
      return mapToReports(reports);

    } catch (Exception e) {
      LOGGER.error("Error running plugin on AIPs", e);
    }

    LOGGER.info("Ended {}", plugin.getName());
    return null;
  }

  @Override
  public List<Report> runPluginOnAllAIPs(Plugin<AIP> plugin) {
    try {
      LOGGER.info("Started {}", plugin.getName());
      int multiplier = 0;
      CloseableIterable<AIP> aips = model.listAIPs();
      Iterator<AIP> iter = aips.iterator();
      List<Future<Object>> futures = new ArrayList<>();
      List<Plugin<AIP>> innerPlugins = new ArrayList<>();
      Plugin<AIP> innerPlugin;

      List<AIP> block = new ArrayList<AIP>();
      while (iter.hasNext()) {
        if (block.size() == BLOCK_SIZE) {
          innerPlugin = getNewPluginInstanceAndRunBeforeExecute(plugin, AIP.class, innerPlugins, BLOCK_SIZE);
          futures.add(Patterns.ask(workersRouter, new PluginMessage<AIP>(block, innerPlugin), DEFAULT_TIMEOUT));
          block = new ArrayList<AIP>();
          multiplier++;
        }

        block.add(iter.next());
      }

      if (!block.isEmpty()) {
        innerPlugin = getNewPluginInstanceAndRunBeforeExecute(plugin, AIP.class, innerPlugins, block.size());
        futures.add(Patterns.ask(workersRouter, new PluginMessage<AIP>(block, innerPlugin), DEFAULT_TIMEOUT));
        multiplier++;
      }

      aips.close();

      final Future<Iterable<Object>> sequenceResult = Futures.sequence(futures, workersSystem.dispatcher());
      Iterable<Object> reports = Await.result(sequenceResult, Duration.create(multiplier * TIMEOUT, TIMEOUT_UNIT));

      for (Plugin<AIP> p : innerPlugins) {
        p.afterExecute(index, model, storage);
      }

      LOGGER.info("Ended {}", plugin.getName());
      return mapToReports(reports);

    } catch (Exception e) {
      LOGGER.error("Error running plugin on all AIPs", e);
    }
    LOGGER.info("Ended " + plugin.getName());
    return null;
  }

  @Override
  public List<Report> runPluginOnAllRepresentations(Plugin<Representation> plugin) {
    try {
      LOGGER.info("Started {}", plugin.getName());
      int multiplier = 0;
      CloseableIterable<AIP> aips = model.listAIPs();
      Iterator<AIP> aipIter = aips.iterator();
      List<Future<Object>> futures = new ArrayList<>();
      List<Plugin<Representation>> innerPlugins = new ArrayList<>();
      Plugin<Representation> innerPlugin;

      List<Representation> block = new ArrayList<Representation>();
      while (aipIter.hasNext()) {
        AIP aip = aipIter.next();
        for (Representation representation : aip.getRepresentations()) {
          if (block.size() == BLOCK_SIZE) {
            innerPlugin = getNewPluginInstanceAndRunBeforeExecute(plugin, Representation.class, innerPlugins,
              BLOCK_SIZE);
            futures
              .add(Patterns.ask(workersRouter, new PluginMessage<Representation>(block, innerPlugin), DEFAULT_TIMEOUT));
            block = new ArrayList<Representation>();
            multiplier++;
          }

          block.add(representation);
        }
      }

      if (!block.isEmpty()) {
        innerPlugin = getNewPluginInstanceAndRunBeforeExecute(plugin, Representation.class, innerPlugins, block.size());
        futures
          .add(Patterns.ask(workersRouter, new PluginMessage<Representation>(block, innerPlugin), DEFAULT_TIMEOUT));
        multiplier++;
      }

      aips.close();

      final Future<Iterable<Object>> sequenceResult = Futures.sequence(futures, workersSystem.dispatcher());
      Iterable<Object> reports = Await.result(sequenceResult, Duration.create(multiplier * TIMEOUT, TIMEOUT_UNIT));

      for (Plugin<Representation> p : innerPlugins) {
        p.afterExecute(index, model, storage);
      }

      LOGGER.info("Ended {}", plugin.getName());
      return mapToReports(reports);

    } catch (Exception e) {
      e.printStackTrace();
      LOGGER.error("Error running plugin on all representations", e);
    }

    LOGGER.info("Ended {}", plugin.getName());
    return null;
  }

  @Override
  public List<Report> runPluginOnAllFiles(Plugin<File> plugin) {
    try {
      LOGGER.info("Started {}", plugin.getName());
      int multiplier = 0;
      CloseableIterable<AIP> aips = model.listAIPs();
      Iterator<AIP> aipIter = aips.iterator();
      List<Future<Object>> futures = new ArrayList<>();
      List<Plugin<File>> innerPlugins = new ArrayList<>();
      Plugin<File> innerPlugin;

      List<File> block = new ArrayList<File>();
      while (aipIter.hasNext()) {
        AIP aip = aipIter.next();
        for (Representation representation : aip.getRepresentations()) {
          boolean recursive = true;
          CloseableIterable<File> files = model.listFilesUnder(aip.getId(), representation.getId(), recursive);
          Iterator<File> fileIter = files.iterator();

          while (fileIter.hasNext()) {

            if (block.size() == BLOCK_SIZE) {
              innerPlugin = getNewPluginInstanceAndRunBeforeExecute(plugin, File.class, innerPlugins, BLOCK_SIZE);
              futures.add(Patterns.ask(workersRouter, new PluginMessage<File>(block, innerPlugin), DEFAULT_TIMEOUT));
              block = new ArrayList<File>();
              multiplier++;
            }

            File file = fileIter.next();
            if (!file.isDirectory()) {
              block.add(file);
            }
          }
          IOUtils.closeQuietly(files);

        }

      }

      if (!block.isEmpty()) {
        innerPlugin = getNewPluginInstanceAndRunBeforeExecute(plugin, File.class, innerPlugins, block.size());
        futures.add(Patterns.ask(workersRouter, new PluginMessage<File>(block, innerPlugin), DEFAULT_TIMEOUT));
        multiplier++;
      }

      aips.close();

      final Future<Iterable<Object>> sequenceResult = Futures.sequence(futures, workersSystem.dispatcher());
      Iterable<Object> reports = Await.result(sequenceResult, Duration.create(multiplier * TIMEOUT, TIMEOUT_UNIT));

      for (Plugin<File> p : innerPlugins) {
        p.afterExecute(index, model, storage);
      }

      LOGGER.info("Ended {}", plugin.getName());
      return mapToReports(reports);

    } catch (Exception e) {
      LOGGER.error("Error running plugin on all files", e);
    }

    LOGGER.info("Ended {}", plugin.getName());
    return null;
  }

  @Override
  public List<Report> runPluginOnTransferredResources(Plugin<TransferredResource> plugin,
    List<TransferredResource> resources) {
    try {
      LOGGER.info("Started {}", plugin.getName());
      int multiplier = 0;
      List<Future<Object>> futures = new ArrayList<>();
      List<Plugin<TransferredResource>> innerPlugins = new ArrayList<>();
      Plugin<TransferredResource> innerPlugin;

      List<TransferredResource> block = new ArrayList<TransferredResource>();
      for (TransferredResource resource : resources) {
        if (block.size() == BLOCK_SIZE) {
          innerPlugin = getNewPluginInstanceAndRunBeforeExecute(plugin, TransferredResource.class, innerPlugins,
            BLOCK_SIZE);
          futures.add(
            Patterns.ask(workersRouter, new PluginMessage<TransferredResource>(block, innerPlugin), DEFAULT_TIMEOUT));
          block = new ArrayList<TransferredResource>();
          multiplier++;
        }

        block.add(resource);
      }

      if (!block.isEmpty()) {
        innerPlugin = getNewPluginInstanceAndRunBeforeExecute(plugin, TransferredResource.class, innerPlugins,
          block.size());
        futures.add(
          Patterns.ask(workersRouter, new PluginMessage<TransferredResource>(block, innerPlugin), DEFAULT_TIMEOUT));
        multiplier++;
      }

      final Future<Iterable<Object>> sequenceResult = Futures.sequence(futures, workersSystem.dispatcher());
      Iterable<Object> reports = Await.result(sequenceResult, Duration.create(multiplier * TIMEOUT, TIMEOUT_UNIT));

      for (Plugin<TransferredResource> p : innerPlugins) {
        p.afterExecute(index, model, storage);
      }

      LOGGER.info("Ended {}", plugin.getName());
      return mapToReports(reports);

    } catch (Exception e) {
      LOGGER.error("Error running plugin on transferred resources", e);
    }

    LOGGER.info("Ended {}", plugin.getName());
    return null;
  }

  @Override
  public void executeJob(Job job) {
    LOGGER.info("Started processing job '{}' ({})", job.getName(), job.getId());

    Future<Object> future = Patterns.ask(jobWorkersRouter, job, getJobTimeout(job));

    future.onSuccess(new OnSuccess<Object>() {
      @Override
      public void onSuccess(Object msg) throws Throwable {
        LOGGER.info("Success executing job '{}' ({})", job.getName(), job.getId());
      }
    }, workersSystem.dispatcher());
    future.onFailure(new OnFailure() {
      @Override
      public void onFailure(Throwable error) throws Throwable {
        LOGGER.error("Failure executing job '{}' ({}): {}", job.getName(), job.getId(), error);
      }
    }, workersSystem.dispatcher());

  }

  private Timeout getJobTimeout(Job job) {
    int objectsCount = job.getObjectIds().size();
    int blocks = 1;
    if (objectsCount != 0) {
      blocks = (objectsCount / BLOCK_SIZE);
      if (objectsCount % BLOCK_SIZE != 0) {
        blocks += 1;
      }
    }

    return new Timeout(Duration.create(TIMEOUT * blocks, TIMEOUT_UNIT));
  }

  @Override
  public void stopJob(Job job) {
    // FIXME 201603 hsilva: this is not the solution as the messages are sent
    // async and until the processing of the current message is done, no other
    // is read
    Patterns.ask(workersRouter, new Broadcast(Kill.getInstance()), DEFAULT_TIMEOUT);
    Patterns.ask(workersRouter, Kill.getInstance(), DEFAULT_TIMEOUT);
  }

  @Override
  public <T extends Serializable> void updateJobPercentage(Plugin<T> plugin, int stepsCompleted, int totalSteps) {
    String jobId = PluginHelper.getJobId(plugin);
    synchronized (runningTasks) {
      Integer totalNumberOfObjects = runningTasksTotalObjects.get(jobId);
      Map<Plugin<?>, JobPluginInfo> map = runningTasks.get(jobId);
      map.get(plugin).setStepsCompleted(stepsCompleted);

      float percentage = 0f;
      for (JobPluginInfo entry : map.values()) {
        float partOne = ((float) entry.getStepsCompleted()) / totalSteps;
        float partTwo = ((float) entry.getNumberOfObjects()) / totalNumberOfObjects;
        percentage += (partOne * partTwo);
      }
      PluginHelper.updateJobStatus(plugin, index, model, (int) (percentage * 100));
    }
  }

  @Override
  public <T extends Serializable> void runPlugin(Plugin<T> plugin) {
    try {
      LOGGER.info("Started {}", plugin.getName());
      plugin.beforeExecute(index, model, storage);

      // FIXME what to do with the askFuture???
      Future<Object> future = Patterns.ask(workersRouter, new PluginMessage<T>(new ArrayList<T>(), plugin),
        DEFAULT_TIMEOUT);

      future.onSuccess(new OnSuccess<Object>() {
        @Override
        public void onSuccess(Object msg) throws Throwable {
          // FIXME this should be sent inside a message that can be easily
          // identified as a list of reports
          if (msg != null && msg instanceof List<?>) {
            LOGGER.info("Success running plugin: {}", (List<Report>) msg);
          }

          plugin.afterExecute(index, model, storage);
          LOGGER.info("Ended {}", plugin.getName());
        }
      }, workersSystem.dispatcher());
      future.onFailure(new OnFailure() {
        @Override
        public void onFailure(Throwable error) throws Throwable {
          LOGGER.error("Failure running plugin: {}", error);

          plugin.afterExecute(index, model, storage);
          LOGGER.info("Ended {}", plugin.getName());
        }
      }, workersSystem.dispatcher());

    } catch (Exception e) {
      LOGGER.error("Error running plugin", e);
    }
  }

  @Override
  public <T extends Serializable> void runPluginOnObjects(Plugin<T> plugin, List<String> ids) {
    // FIXME
    LOGGER.error("Method runPluginOnObjects@{} still not implemented!", this.getClass().getName());
  }

  private List<Report> mapToReports(Iterable<Object> reports) {
    List<Report> ret;
    ret = new ArrayList<>();
    for (Object o : reports) {
      if (o instanceof Report) {
        ret.add((Report) o);
      } else {
        LOGGER.warn("Got a response that was not a report: {}", o.getClass().getName());
      }
    }
    return ret;
  }

  class JobPluginInfo {
    private int numberOfObjects;
    private int stepsCompleted;
    private int totalSteps;

    public JobPluginInfo() {

    }

    public int getNumberOfObjects() {
      return numberOfObjects;
    }

    public void setNumberOfObjects(int numberOfObjects) {
      this.numberOfObjects = numberOfObjects;
    }

    public int getStepsCompleted() {
      return stepsCompleted;
    }

    public void setStepsCompleted(int stepsCompleted) {
      this.stepsCompleted = stepsCompleted;
    }

    public int getTotalSteps() {
      return totalSteps;
    }

    public void setTotalSteps(int totalSteps) {
      this.totalSteps = totalSteps;
    }

  }

  public class PluginMessage<T extends Serializable> {
    private List<T> list;
    private Plugin<T> plugin;

    public PluginMessage(List<T> list, Plugin<T> plugin) {
      this.list = list;
      this.plugin = plugin;
    }

    public List<T> getList() {
      return list;
    }

    public void setList(List<T> list) {
      this.list = list;
    }

    public Plugin<T> getPlugin() {
      return plugin;
    }

    public void setPlugin(Plugin<T> plugin) {
      this.plugin = plugin;
    }
  }

}
