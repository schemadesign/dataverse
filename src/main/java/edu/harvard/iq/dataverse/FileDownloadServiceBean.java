package edu.harvard.iq.dataverse;

import edu.harvard.iq.dataverse.authorization.AuthenticationServiceBean;
import edu.harvard.iq.dataverse.authorization.Permission;
import edu.harvard.iq.dataverse.authorization.users.ApiToken;
import edu.harvard.iq.dataverse.authorization.users.AuthenticatedUser;
import edu.harvard.iq.dataverse.authorization.users.User;
import edu.harvard.iq.dataverse.datasetutility.WorldMapPermissionHelper;
import edu.harvard.iq.dataverse.engine.command.exception.CommandException;
import edu.harvard.iq.dataverse.engine.command.impl.CreateGuestbookResponseCommand;
import edu.harvard.iq.dataverse.engine.command.impl.RequestAccessCommand;
import edu.harvard.iq.dataverse.externaltools.ExternalTool;
import edu.harvard.iq.dataverse.externaltools.ExternalToolHandler;
import edu.harvard.iq.dataverse.util.FileUtil;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.faces.context.FacesContext;
import javax.inject.Inject;
import javax.inject.Named;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;

/**
 *
 * @author skraffmi
 * Handles All File Download processes
 * including Guestbook responses
 */
@Stateless
@Named
public class FileDownloadServiceBean implements java.io.Serializable {

    @PersistenceContext(unitName = "VDCNet-ejbPU")
    private EntityManager em;
    
    @EJB
    GuestbookResponseServiceBean guestbookResponseService;
    @EJB
    DatasetServiceBean datasetService;
    @EJB
    DatasetVersionServiceBean datasetVersionService;
    @EJB
    DataFileServiceBean datafileService;
    @EJB
    PermissionServiceBean permissionService;
    @EJB
    DataverseServiceBean dataverseService;
    @EJB
    UserNotificationServiceBean userNotificationService;
    @EJB
    AuthenticationServiceBean authService;
    
    @Inject
    DataverseSession session;
    
    @EJB
    EjbDataverseEngine commandEngine;
    
    @Inject
    DataverseRequestServiceBean dvRequestService;
    
    @Inject WorldMapPermissionHelper worldMapPermissionHelper;
    @Inject FileDownloadHelper fileDownloadHelper;

    private static final Logger logger = Logger.getLogger(FileDownloadServiceBean.class.getCanonicalName());   
    
    
    public void writeGuestbookAndStartDownload(GuestbookResponse guestbookResponse){

        if (guestbookResponse != null && guestbookResponse.getDataFile() != null     ){
        	logger.info("File: " + guestbookResponse.getDataFile().getDisplayName());
            writeGuestbookResponseRecord(guestbookResponse);
            // Make sure to set the "do not write Guestbook response" flag to TRUE when calling the Access API:
            if(guestbookResponse.getDataFile()!=null) {
            logger.info("downloading " + guestbookResponse.getDataFile().getDisplayName());
            } else {
            	logger.info("File now null");
            }
            callDownloadServlet(guestbookResponse.getFileFormat(), guestbookResponse.getDataFile().getId(), true);
        }
        
        if (guestbookResponse != null && guestbookResponse.getSelectedFileIds() != null     ){
            List<String> list = new ArrayList<>(Arrays.asList(guestbookResponse.getSelectedFileIds().split(",")));

            for (String idAsString : list) {
                DataFile df = datafileService.findCheapAndEasy(new Long(idAsString)) ;
                if (df != null) {
                    guestbookResponse.setDataFile(df);
                    writeGuestbookResponseRecord(guestbookResponse);
                }
            }
            logger.info("Downloading multiple files");
            callDownloadServlet(guestbookResponse.getSelectedFileIds(), true);
        }
        
        
    }
    
    public void writeGuestbookResponseRecord(GuestbookResponse guestbookResponse) {

        try {
            CreateGuestbookResponseCommand cmd = new CreateGuestbookResponseCommand(dvRequestService.getDataverseRequest(), guestbookResponse, guestbookResponse.getDataset());
            commandEngine.submit(cmd);
        } catch (CommandException e) {
            //if an error occurs here then download won't happen no need for response recs...

        }
    }
    public void callDownloadServlet(String multiFileString, Boolean gbRecordsWritten){
        String fileDownloadUrl = "/api/access/datafiles/" + multiFileString;
        if (gbRecordsWritten){
            fileDownloadUrl += "?gbrecs=true";
        }
        try {
        	FacesContext fc = FacesContext.getCurrentInstance();
        	if(!fc.getResponseComplete()) {
            fc.getExternalContext().redirect(fileDownloadUrl);
        	} else {
        		logger.info("Response complete before redirect call");
        	}
        } catch (IOException ex) {
            logger.info("Failed to issue a redirect to file download url.");
        }

        //return fileDownloadUrl;
    }

    // The "doNotWriteGuestBookRecord" parameter is passed to the API. 
    // As of now (May 2018) we always set this flag to true when redirecting the 
    // user to the Access API. That's because we have either just created the 
    // record ourselves, on the application side; or we have skipped creating one, 
    // because this was a draft file and we don't want to count the download. 
    // But either way, it is NEVER the API side's job to count the download that 
    // was initiated in the GUI. 
    // But note that this may change - there may be some future situations where it will 
    // become necessary again, to pass the job of creating the access record 
    // to the API!
    public void callDownloadServlet(String downloadType, Long fileId, boolean doNotWriteGuestBookRecord) {
        String fileDownloadUrl = FileUtil.getFileDownloadUrlPath(downloadType, fileId, doNotWriteGuestBookRecord);
        logger.fine("Redirecting to file download url: " + fileDownloadUrl);
        try {
            FacesContext.getCurrentInstance().getExternalContext().redirect(fileDownloadUrl);
        } catch (IOException ex) {
            logger.info("Failed to issue a redirect to file download url (" + fileDownloadUrl + "): " + ex);
        }
    }

    
    public void startFileDownload(GuestbookResponse guestbookResponse, FileMetadata fileMetadata, String format) {
        if(!fileMetadata.getDatasetVersion().isDraft()){
           guestbookResponse = guestbookResponseService.modifyDatafileAndFormat(guestbookResponse, fileMetadata, format);
           writeGuestbookResponseRecord(guestbookResponse);
        }
        // Make sure to set the "do not write Guestbook response" flag to TRUE when calling the Access API:
        callDownloadServlet(format, fileMetadata.getDataFile().getId(), true);
        logger.fine("issued file download redirect for filemetadata "+fileMetadata.getId()+", datafile "+fileMetadata.getDataFile().getId());
    }

    /**
     * Launch an "explore" tool which is a type of ExternalTool such as
     * TwoRavens or Data Explorer. This method may be invoked directly from the
     * xhtml if no popup is required (no terms of use, no guestbook, etc.).
     */
    public void explore(GuestbookResponse guestbookResponse, FileMetadata fmd, ExternalTool externalTool) {
        ApiToken apiToken = null;
        User user = session.getUser();
        if (user instanceof AuthenticatedUser) {
            AuthenticatedUser authenticatedUser = (AuthenticatedUser) user;
            apiToken = authService.findApiTokenByUser(authenticatedUser);
        }
        DataFile dataFile = null;
        if (fmd != null) {
            dataFile = fmd.getDataFile();
        } else {
            if (guestbookResponse != null) {
                dataFile = guestbookResponse.getDataFile();
            }
        }
        ExternalToolHandler externalToolHandler = new ExternalToolHandler(externalTool, dataFile, apiToken);
        // Back when we only had TwoRavens, the downloadType was always "Explore". Now we persist the name of the tool (i.e. "TwoRavens", "Data Explorer", etc.)
        guestbookResponse.setDownloadtype(externalTool.getDisplayName());
        String toolUrl = externalToolHandler.getToolUrlWithQueryParams();
        logger.fine("Exploring with " + toolUrl);
        try {
            FacesContext.getCurrentInstance().getExternalContext().redirect(toolUrl);
        } catch (IOException ex) {
            logger.info("Problem exploring with " + toolUrl + " - " + ex);
        }
        // This is the old logic from TwoRavens, null checks and all.
        if (guestbookResponse != null && guestbookResponse.isWriteResponse()
                && ((fmd != null && fmd.getDataFile() != null) || guestbookResponse.getDataFile() != null)) {
            if (guestbookResponse.getDataFile() == null && fmd != null) {
                guestbookResponse.setDataFile(fmd.getDataFile());
            }
            if (fmd == null || !fmd.getDatasetVersion().isDraft()) {
                writeGuestbookResponseRecord(guestbookResponse);
            }
        }
    }

    public String startWorldMapDownloadLink(GuestbookResponse guestbookResponse, FileMetadata fmd){
                
        if (guestbookResponse != null  && guestbookResponse.isWriteResponse() && ((fmd != null && fmd.getDataFile() != null) || guestbookResponse.getDataFile() != null)){
            if(guestbookResponse.getDataFile() == null && fmd != null){
                guestbookResponse.setDataFile(fmd.getDataFile());
            }
            if (fmd == null || !fmd.getDatasetVersion().isDraft()){
                writeGuestbookResponseRecord(guestbookResponse);
            }
        }
        DataFile file = null;
        if (fmd != null){
            file  = fmd.getDataFile();
        }
        if (guestbookResponse != null && guestbookResponse.getDataFile() != null && file == null){
            file  = guestbookResponse.getDataFile();
        }
        

        String retVal = worldMapPermissionHelper.getMapLayerMetadata(file).getLayerLink();
        
        try {
            FacesContext.getCurrentInstance().getExternalContext().redirect(retVal);
            return retVal;
        } catch (IOException ex) {
            logger.info("Failed to issue a redirect to file download url.");
        }
        return retVal;
    }

    public Boolean canSeeTwoRavensExploreButton(){
        return false;
    }
    
    
    public Boolean canUserSeeExploreWorldMapButton(){
        return false;
    }
    
    public void downloadDatasetCitationXML(Dataset dataset) {
        downloadCitationXML(null, dataset, false);
    }

    public void downloadDatafileCitationXML(FileMetadata fileMetadata) {
        downloadCitationXML(fileMetadata, null, false);
    }
    
    public void downloadDirectDatafileCitationXML(FileMetadata fileMetadata) {
        downloadCitationXML(fileMetadata, null, true);
    }

    public void downloadCitationXML(FileMetadata fileMetadata, Dataset dataset, boolean direct) {
    	DataCitation citation=null;
        if (dataset != null){
        	citation = new DataCitation(dataset.getLatestVersion());
        } else {
            citation= new DataCitation(fileMetadata, direct);
        }
        FacesContext ctx = FacesContext.getCurrentInstance();
        HttpServletResponse response = (HttpServletResponse) ctx.getExternalContext().getResponse();
        response.setContentType("text/xml");
        String fileNameString;
        if (fileMetadata == null || fileMetadata.getLabel() == null) {
            // Dataset-level citation: 
            fileNameString = "attachment;filename=" + getFileNameDOI(citation.getPersistentId()) + ".xml";
        } else {
            // Datafile-level citation:
            fileNameString = "attachment;filename=" + getFileNameDOI(citation.getPersistentId()) + "-" + FileUtil.getCiteDataFileFilename(citation.getFileTitle(), FileUtil.FileCitationExtension.ENDNOTE);
        }
        response.setHeader("Content-Disposition", fileNameString);
        try {
            ServletOutputStream out = response.getOutputStream();
            citation.writeAsEndNoteCitation(out);
            out.flush();
            ctx.responseComplete();
        } catch (IOException e) {

        }
    }
    
    public void downloadDatasetCitationRIS(Dataset dataset) {

        downloadCitationRIS(null, dataset, false);

    }

    public void downloadDatafileCitationRIS(FileMetadata fileMetadata) {
        downloadCitationRIS(fileMetadata, null, false);
    }
    
    public void downloadDirectDatafileCitationRIS(FileMetadata fileMetadata) {
        downloadCitationRIS(fileMetadata, null, true);
    }

    public void downloadCitationRIS(FileMetadata fileMetadata, Dataset dataset, boolean direct) {
    	DataCitation citation=null;
        if (dataset != null){
        	citation = new DataCitation(dataset.getLatestVersion());
        } else {
            citation= new DataCitation(fileMetadata, direct);
        }

        FacesContext ctx = FacesContext.getCurrentInstance();
        HttpServletResponse response = (HttpServletResponse) ctx.getExternalContext().getResponse();
        response.setContentType("application/download");

        String fileNameString;
        if (fileMetadata == null || fileMetadata.getLabel() == null) {
            // Dataset-level citation: 
            fileNameString = "attachment;filename=" + getFileNameDOI(citation.getPersistentId()) + ".ris";
        } else {
            // Datafile-level citation:
            fileNameString = "attachment;filename=" + getFileNameDOI(citation.getPersistentId()) + "-" + FileUtil.getCiteDataFileFilename(citation.getFileTitle(), FileUtil.FileCitationExtension.RIS);
        }
        response.setHeader("Content-Disposition", fileNameString);

        try {
            ServletOutputStream out = response.getOutputStream();
            citation.writeAsRISCitation(out);
            out.flush();
            ctx.responseComplete();
        } catch (IOException e) {

        }
    }
    
    private String getFileNameDOI(GlobalId id) {
        return "DOI:" + id.getAuthority() + "_" + id.getIdentifier();
    }

    public void downloadDatasetCitationBibtex(Dataset dataset) {

        downloadCitationBibtex(null, dataset, false);

    }

    public void downloadDatafileCitationBibtex(FileMetadata fileMetadata) {
        downloadCitationBibtex(fileMetadata, null, false);
    }

    public void downloadDirectDatafileCitationBibtex(FileMetadata fileMetadata) {
        downloadCitationBibtex(fileMetadata, null, true);
    }
    
    public void downloadCitationBibtex(FileMetadata fileMetadata, Dataset dataset, boolean direct) {
    	DataCitation citation=null;
        if (dataset != null){
        	citation = new DataCitation(dataset.getLatestVersion());
        } else {
            citation= new DataCitation(fileMetadata, direct);
        }
        
        FacesContext ctx = FacesContext.getCurrentInstance();
        HttpServletResponse response = (HttpServletResponse) ctx.getExternalContext().getResponse();
        response.setContentType("application/download");

        String fileNameString;
        if (fileMetadata == null || fileMetadata.getLabel() == null) {
            // Dataset-level citation:
            fileNameString = "attachment;filename=" + getFileNameDOI(citation.getPersistentId()) + ".bib";
        } else {
            // Datafile-level citation:
            fileNameString = "attachment;filename=" + getFileNameDOI(citation.getPersistentId()) + "-" + FileUtil.getCiteDataFileFilename(citation.getFileTitle(), FileUtil.FileCitationExtension.BIBTEX);
        }
        response.setHeader("Content-Disposition", fileNameString);

        try {
            ServletOutputStream out = response.getOutputStream();
            citation.writeAsBibtexCitation(out);
            out.flush();
            ctx.responseComplete();
        } catch (IOException e) {

        }
    }
    
    public boolean requestAccess(Long fileId) {   
        if (dvRequestService.getDataverseRequest().getAuthenticatedUser() == null){
            return false;
        }
        DataFile file = datafileService.find(fileId);
        if (!file.getFileAccessRequesters().contains((AuthenticatedUser)session.getUser())) {
            try {
                commandEngine.submit(new RequestAccessCommand(dvRequestService.getDataverseRequest(), file));                        
                return true;
            } catch (CommandException ex) {
                logger.info("Unable to request access for file id " + fileId + ". Exception: " + ex);
            }             
        }
        
        return false;
    }    
    
    public void sendRequestFileAccessNotification(Dataset dataset, Long fileId) {
        permissionService.getUsersWithPermissionOn(Permission.ManageDatasetPermissions, dataset).stream().forEach((au) -> {
            userNotificationService.sendNotification(au, new Timestamp(new Date().getTime()), UserNotification.Type.REQUESTFILEACCESS, fileId);
        });

    }    


    
}