/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE file at the root of the source
 * tree and available online at
 *
 * https://github.com/keeps/roda
 */
package org.roda.core.plugins.plugins.ingest;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;


import org.apache.directory.api.ldap.aci.Permission;
import org.apache.jute.Index;
import org.apache.poi.hpsf.IllegalPropertySetDataException;
import org.roda.core.RodaCoreFactory;
import org.roda.core.data.adapter.filter.Filter;
import org.roda.core.data.adapter.filter.SimpleFilterParameter;
import org.roda.core.data.adapter.sort.Sorter;
import org.roda.core.data.adapter.sublist.Sublist;
import org.roda.core.data.common.RodaConstants;
import org.roda.core.data.exceptions.AlreadyExistsException;
import org.roda.core.data.exceptions.AuthorizationDeniedException;
import org.roda.core.data.exceptions.GenericException;
import org.roda.core.data.exceptions.InvalidParameterException;
import org.roda.core.data.exceptions.NotFoundException;
import org.roda.core.data.exceptions.RequestNotValidException;
import org.roda.core.data.v2.index.IndexResult;
import org.roda.core.data.v2.ip.AIP;
import org.roda.core.data.v2.ip.AIPState;
import org.roda.core.data.v2.ip.IndexedAIP;
import org.roda.core.data.v2.ip.Permissions;
import org.roda.core.data.v2.ip.StoragePath;
import org.roda.core.data.v2.ip.TransferredResource;
import org.roda.core.data.v2.jobs.Job;
import org.roda.core.data.v2.jobs.Report;
import org.roda.core.data.v2.jobs.Report.PluginState;
import org.roda.core.data.v2.validation.ValidationException;
import org.roda.core.index.IndexService;
import org.roda.core.model.ModelService;
import org.roda.core.plugins.Plugin;
import org.roda.core.plugins.PluginException;
import org.roda.core.plugins.plugins.PluginHelper;
import org.roda.core.storage.DefaultStoragePath;
import org.roda.core.storage.Directory;
import org.roda.core.storage.StorageService;
import org.roda.core.storage.StorageServiceUtils;
import org.roda.core.storage.fs.FSUtils;
import org.roda_project.commons_ip.model.SIP;
import org.roda_project.commons_ip.model.impl.eark.EARKSIP;
import org.roda_project.commons_ip.utils.IPEnums;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EARKSIPToAIPPlugin extends SIPToAIPPlugin {
  private static final Logger LOGGER = LoggerFactory.getLogger(EARKSIPToAIPPlugin.class);

  public static String UNPACK_DESCRIPTION = "Extracted objects from package in E-ARK SIP format.";

  private boolean createSubmission = false;

  @Override
  public void init() throws PluginException {
  }

  @Override
  public void shutdown() {
    // do nothing
  }

  @Override
  public String getName() {
    return "E-ARK SIP";
  }

  @Override
  public String getDescription() {
    return "E-ARK SIP as a zip file";
  }

  @Override
  public String getVersionImpl() {
    return "1.0";
  }

  @Override
  public void setParameterValues(Map<String, String> parameters) throws InvalidParameterException {
    super.setParameterValues(parameters);

    if (getParameterValues().containsKey(RodaConstants.PLUGIN_PARAMS_CREATE_SUBMISSION)) {
      createSubmission = Boolean.parseBoolean(getParameterValues().get(RodaConstants.PLUGIN_PARAMS_CREATE_SUBMISSION));
    }
  }

  @Override
  public Report beforeAllExecute(IndexService index, ModelService model, StorageService storage)
    throws PluginException {
    // do nothing
    return null;
  }

  @Override
  public Report execute(IndexService index, ModelService model, StorageService storage, List<TransferredResource> list)
    throws PluginException {
    Report report = PluginHelper.initPluginReport(this);

    for (TransferredResource transferredResource : list) {
      Report reportItem = PluginHelper.initPluginReportItem(this, transferredResource);

      Path earkSIPPath = Paths.get(transferredResource.getFullPath());
      LOGGER.debug("Converting {} to AIP", earkSIPPath);

      transformTransferredResourceIntoAnAIP(index, model, storage, transferredResource, earkSIPPath, createSubmission,
        reportItem);
      report.addReport(reportItem);

      PluginHelper.createJobReport(this, model, reportItem);

    }
    return report;
  }

  private void transformTransferredResourceIntoAnAIP(IndexService index, ModelService model, StorageService storage,
    TransferredResource transferredResource, Path earkSIPPath, boolean createSubmission, Report reportItem) {
    SIP sip = null;
    try {
      sip = EARKSIP.parse(earkSIPPath, RodaCoreFactory.getWorkingDirectory());
      reportItem.setSourceObjectOriginalId(sip.getId());
      createAncestors(sip, index, model, storage);

      if (sip.getValidationReport().isValid()) {
        String sipParentId = sip.getAncestors() != null && sip.getAncestors().isEmpty() ? "" : sip.getAncestors().get(0);
        String computedParentId = PluginHelper.computeParentId(this, index, sipParentId);

        AIP aip;

        if(IPEnums.IPStatus.UPDATE == sip.getStatus()){
          IndexResult<IndexedAIP> result = index.find(IndexedAIP.class, new Filter(new SimpleFilterParameter(RodaConstants.INGEST_SIP_ID, sip.getId())), Sorter.NONE, new Sublist(0,1));
          if(result.getTotalCount() == 1) {
            // Retrieve the AIP
            IndexedAIP indexedAIP = result.getResults().get(0);
            aip = EARKSIPToAIPPluginUtils.earkSIPToAIPUpdate(sip, indexedAIP.getId(), earkSIPPath, model, storage,
              sip.getId(), reportItem.getJobId(), computedParentId);
          } else {
            // Fail to update since there's no AIP
            throw new NotFoundException("Unable to find AIP created with SIP ID: " + sip.getId());
          }
        }else if (IPEnums.IPStatus.NEW == sip.getStatus()){
          // Create a new AIP
          aip = EARKSIPToAIPPluginUtils.earkSIPToAIP(sip, earkSIPPath, model, storage, sip.getId(), reportItem.getJobId(), computedParentId);
        }else {
          throw new GenericException("Unknown IP Status: " + sip.getStatus());
        }

        PluginHelper.createSubmission(model, createSubmission, earkSIPPath, aip.getId());

        createUnpackingEventSuccess(model, index, transferredResource, aip, UNPACK_DESCRIPTION);
        reportItem.setOutcomeObjectId(aip.getId()).setPluginState(PluginState.SUCCESS);

        if (sip.getAncestors() != null && !sip.getAncestors().isEmpty() && aip.getParentId() == null) {
          reportItem.setPluginDetails(String.format("Parent with id '%s' not found", sipParentId));
        }
        createWellformedEventSuccess(model, index, transferredResource, aip);
        LOGGER.debug("Done with converting {} to AIP {}", earkSIPPath, aip.getId());
      } else {
        reportItem.setPluginState(PluginState.FAILURE).setHtmlPluginDetails(true)
          .setPluginDetails(sip.getValidationReport().toHtml(true, true, true, false, false));
        LOGGER.debug("The SIP {} is not valid", earkSIPPath);
      }

    } catch (Throwable e) {
      reportItem.setPluginState(PluginState.FAILURE).setPluginDetails(e.getMessage());
      LOGGER.error("Error converting " + earkSIPPath + " to AIP", e);
    } finally {
      if (sip != null) {
        Path transferredResourcesAbsolutePath = RodaCoreFactory.getTransferredResourcesScanner().getBasePath()
          .toAbsolutePath();
        if (!sip.getBasePath().toAbsolutePath().toString().startsWith(transferredResourcesAbsolutePath.toString())) {
          FSUtils.deletePathQuietly(sip.getBasePath());
        }
      }
    }
  }

  private void createAncestors(SIP sip, IndexService index, ModelService model, StorageService storage)
          throws GenericException, RequestNotValidException, AuthorizationDeniedException, NotFoundException, AlreadyExistsException, ValidationException {
    List<String> ancestors = new ArrayList<>(sip.getAncestors());
    if(ancestors.isEmpty())
      return;
    // Reverse list so that the top ancestors come first
    Collections.reverse(ancestors);
    String parent = null;

    for (String ancestor : ancestors) {
      try {
        model.retrieveAIP(ancestor);
        parent = ancestor;
      } catch (NotFoundException e) {
        Job currentJob = PluginHelper.getJobFromIndex(this, index);
        if(currentJob == null){
          throw new GenericException("Job is null");
        }
        String username = currentJob.getUsername();
        Permissions permissions = new Permissions();

        permissions.setUserPermissions(username, new HashSet<>(Arrays.asList(
                Permissions.PermissionType.CREATE,
                Permissions.PermissionType.READ,
                Permissions.PermissionType.UPDATE,
                Permissions.PermissionType.DELETE,
                Permissions.PermissionType.GRANT)));
        model.createAIP(ancestor, parent, "", permissions, true);
        parent = ancestor;
      }
    }
  }

  @Override
  public Report afterAllExecute(IndexService index, ModelService model, StorageService storage) throws PluginException {
    // do nothing
    return null;
  }

  @Override
  public Plugin<TransferredResource> cloneMe() {
    return new EARKSIPToAIPPlugin();
  }

  @Override
  public boolean areParameterValuesValid() {
    return true;
  }

  @Override
  public List<String> getCategories() {
    return Arrays.asList(RodaConstants.PLUGIN_CATEGORY_NOT_LISTABLE);
  }

  @Override
  public List<Class<TransferredResource>> getObjectClasses() {
    return Arrays.asList(TransferredResource.class);
  }
}
