/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE file at the root of the source
 * tree and available online at
 *
 * https://github.com/keeps/roda
 */
package org.roda.core.plugins.plugins.characterization;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.roda.core.common.iterables.CloseableIterable;
import org.roda.core.data.common.RodaConstants;
import org.roda.core.data.common.RodaConstants.PreservationEventType;
import org.roda.core.data.exceptions.AuthorizationDeniedException;
import org.roda.core.data.exceptions.GenericException;
import org.roda.core.data.exceptions.JobException;
import org.roda.core.data.exceptions.NotFoundException;
import org.roda.core.data.exceptions.RequestNotValidException;
import org.roda.core.data.v2.common.OptionalWithCause;
import org.roda.core.data.v2.ip.AIP;
import org.roda.core.data.v2.ip.AIPState;
import org.roda.core.data.v2.ip.File;
import org.roda.core.data.v2.ip.Representation;
import org.roda.core.data.v2.ip.StoragePath;
import org.roda.core.data.v2.jobs.PluginType;
import org.roda.core.data.v2.jobs.Report;
import org.roda.core.data.v2.jobs.Report.PluginState;
import org.roda.core.data.v2.validation.ValidationIssue;
import org.roda.core.data.v2.validation.ValidationReport;
import org.roda.core.index.IndexService;
import org.roda.core.model.ModelService;
import org.roda.core.model.utils.ModelUtils;
import org.roda.core.plugins.AbstractPlugin;
import org.roda.core.plugins.Plugin;
import org.roda.core.plugins.PluginException;
import org.roda.core.plugins.orchestrate.SimpleJobPluginInfo;
import org.roda.core.plugins.plugins.PluginHelper;
import org.roda.core.storage.Binary;
import org.roda.core.storage.ContentPayload;
import org.roda.core.storage.StorageService;
import org.roda.core.storage.fs.FSPathContentPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JHOVEPlugin extends AbstractPlugin<AIP> {
  private static final Logger LOGGER = LoggerFactory.getLogger(JHOVEPlugin.class);

  @Override
  public void init() throws PluginException {
  }

  @Override
  public void shutdown() {
    // do nothing
  }

  @Override
  public String getName() {
    return "AIP feature extraction (JHOVE)";
  }

  @Override
  public String getDescription() {
    return "JHOVE, the JSTOR/Harvard Object Validation Environment, is an extensible software framework for performing format characterization of digital objects.\nJHOVE includes modules for arbitrary byte streams, ASCII and UTF-8 encoded text, TIFF, HTML, XML, JPEG, JPEG2000, PDF, AIFF, WAVE audio; and text and XML output handlers. \nThe task updates PREMIS objects metadata in the Archival Information Package (AIP) to store the results of the characterization process. A PREMIS event is also recorded after the task is run.\nFor more information on this tool, please visit http://jhove.openpreservation.org ";
  }

  @Override
  public String getVersionImpl() {
    return "1.0";
  }

  @Override
  public Report execute(IndexService index, ModelService model, StorageService storage, List<AIP> list)
    throws PluginException {

    Report report = PluginHelper.initPluginReport(this);

    try {
      SimpleJobPluginInfo jobPluginInfo = PluginHelper.getInitialJobInformation(this, list.size());
      PluginHelper.updateJobInformation(this, jobPluginInfo);

      try {
        for (AIP aip : list) {
          LOGGER.debug("Processing AIP {}", aip.getId());
          boolean inotify = false;
          Report reportItem = PluginHelper.initPluginReportItem(this, aip.getId(), AIP.class,
            AIPState.INGEST_PROCESSING);
          PluginHelper.updatePartialJobReport(this, model, index, reportItem, false);
          PluginState reportState = PluginState.SUCCESS;
          ValidationReport validationReport = new ValidationReport();

          try {
            for (Representation representation : aip.getRepresentations()) {
              LOGGER.debug("Processing representation {} from AIP {}", representation.getId(), aip.getId());
              boolean recursive = true;
              CloseableIterable<OptionalWithCause<File>> allFiles = model.listFilesUnder(aip.getId(),
                representation.getId(), recursive);
              for (OptionalWithCause<File> oFile : allFiles) {
                if (oFile.isPresent()) {
                  File file = oFile.get();
                  if (!file.isDirectory()) {
                    LOGGER.debug("Processing file: {}", file);
                    StoragePath storagePath = ModelUtils.getFileStoragePath(file);
                    Binary binary = storage.getBinary(storagePath);

                    Path jhoveResults = JHOVEPluginUtils.runJhove(file, binary, getParameterValues());
                    ContentPayload payload = new FSPathContentPayload(jhoveResults);
                    model.createOtherMetadata(aip.getId(), representation.getId(), file.getPath(), file.getId(), ".xml",
                      RodaConstants.OTHER_METADATA_TYPE_JHOVE, payload, inotify);
                    jhoveResults.toFile().delete();
                  }
                } else {
                  LOGGER.error("Cannot process AIP representation file", oFile.getCause());
                }
              }
              IOUtils.closeQuietly(allFiles);
            }
          } catch (Exception e) {
            LOGGER.error("Error processing AIP: " + aip.getId(), e);
            reportState = PluginState.FAILURE;
            validationReport.addIssue(new ValidationIssue(e.getMessage()));
          }

          try {
            model.notifyAIPUpdated(aip.getId());
          } catch (RequestNotValidException | GenericException | NotFoundException | AuthorizationDeniedException e) {
            LOGGER.error("Error notifying of AIP update", e);
          }

          if (reportState.equals(PluginState.SUCCESS)) {
            jobPluginInfo.incrementObjectsProcessedWithSuccess();
            reportItem.setPluginState(PluginState.SUCCESS);
          } else {
            jobPluginInfo.incrementObjectsProcessedWithFailure();
            reportItem.setHtmlPluginDetails(true).setPluginState(PluginState.FAILURE);
            reportItem.setPluginDetails(validationReport.toHtml(false, false, false, "Error list"));
          }

          report.addReport(reportItem);
          PluginHelper.updatePartialJobReport(this, model, index, reportItem, true);
        }
      } catch (ClassCastException e) {
        LOGGER.error("Trying to execute an AIP-only plugin with other objects");
        jobPluginInfo.incrementObjectsProcessedWithFailure(list.size());
      }

      jobPluginInfo.finalizeInfo();
      PluginHelper.updateJobInformation(this, jobPluginInfo);
    } catch (JobException e) {
      throw new PluginException("A job exception has occurred", e);
    }

    return report;
  }

  @Override
  public Plugin<AIP> cloneMe() {
    return new JHOVEPlugin();
  }

  @Override
  public PluginType getType() {
    return PluginType.AIP_TO_AIP;
  }

  @Override
  public boolean areParameterValuesValid() {
    return true;
  }

  // TODO FIX
  @Override
  public PreservationEventType getPreservationEventType() {
    return null;
  }

  @Override
  public String getPreservationEventDescription() {
    return "XXXXXXXXXX";
  }

  @Override
  public String getPreservationEventSuccessMessage() {
    return "XXXXXXXXXXXXXXXXXXXXXXXX";
  }

  @Override
  public String getPreservationEventFailureMessage() {
    return "XXXXXXXXXXXXXXXXXXXXXXXXXX";
  }

  @Override
  public Report beforeAllExecute(IndexService index, ModelService model, StorageService storage)
    throws PluginException {
    // do nothing
    return null;
  }

  @Override
  public Report afterAllExecute(IndexService index, ModelService model, StorageService storage) throws PluginException {
    // do nothing
    return null;
  }

  @Override
  public List<String> getCategories() {
    return Arrays.asList(RodaConstants.PLUGIN_CATEGORY_CHARACTERIZATION);
  }

  @Override
  public List<Class<AIP>> getObjectClasses() {
    return Arrays.asList(AIP.class);
  }

}
