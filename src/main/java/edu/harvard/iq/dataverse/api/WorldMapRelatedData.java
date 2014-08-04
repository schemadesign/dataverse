/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.harvard.iq.dataverse.api;

import edu.harvard.iq.dataverse.DataFile;
import edu.harvard.iq.dataverse.DataFileServiceBean;
import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.DatasetServiceBean;
import edu.harvard.iq.dataverse.DatasetVersion;
import edu.harvard.iq.dataverse.Dataverse;
import edu.harvard.iq.dataverse.DataverseUser;
import edu.harvard.iq.dataverse.FileMetadata;
import edu.harvard.iq.dataverse.MapLayerMetadata;
import edu.harvard.iq.dataverse.MapLayerMetadataServiceBean;
import java.io.StringReader;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import javax.json.stream.JsonParsingException;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import org.atmosphere.config.service.Post;
import org.atmosphere.config.service.Put;
import org.primefaces.json.JSONObject;

/**
 *
 * @author rmp553
 */
@Path("worldmap")
public class WorldMapRelatedData extends AbstractApiBean {

    private static final Logger logger = Logger.getLogger(Files.class.getCanonicalName());

    @EJB
    MapLayerMetadataServiceBean mapLayerMetadataService;

    @EJB
    DatasetServiceBean datasetService;

    @EJB
    DataFileServiceBean dataFileService;
    
    
    @GET
    //@Path("{id}")
    @Path("datafile/{datafile_id}")
    /*
        For WorldMap/GeoConnect Usage
        Return detailed Datafile information including latest Dataset and Dataverse data
        
        !! Does not yet implement permissions/command checks
        !! Change to POST with check for hidden WorldMap key; IP check, etc
    */
    public Response getWorldMapDatafile(@PathParam("datafile_id") Long datafile_id, @QueryParam("key") String apiKey) {
        
        // Temp: Check if the user exists
        // Change this to WorldMap API check!!
        DataverseUser dv_user = userSvc.findByUserName(apiKey);
        if (dv_user == null) {
            return errorResponse(Response.Status.UNAUTHORIZED, "Invalid apikey '" + apiKey + "'");
        }
        
        // (1) Attempt to retrieve DataFile indicated by id
        DataFile dfile = dataFileService.find(datafile_id);
        if (dfile==null){
           return errorResponse(Response.Status.NOT_FOUND, "DataFile not found for id: " + datafile_id);
        }
        FileMetadata dfile_meta = dfile.getFileMetadata();
        if (dfile_meta==null){
           return errorResponse(Response.Status.NOT_FOUND, "FileMetadata not found");
        }
        
        // (2) Now get the dataset and the latest DatasetVersion
        Dataset dset = dfile.getOwner();
        if (dset==null){
            return errorResponse(Response.Status.NOT_FOUND, "Owning Dataset for this DataFile not found");
        }
        
        // (2a) latest DatasetVersion
        // !! How do you check if the lastest version has this specific file?
        //
        DatasetVersion dset_version = dset.getLatestVersion();
        if (dset==null){
            return errorResponse(Response.Status.NOT_FOUND, "Latest DatasetVersion for this DataFile not found");
        }
        
        // (3) get Dataverse
        Dataverse dverse = dset.getOwner();
        if (dverse==null){
            return errorResponse(Response.Status.NOT_FOUND, "Dataverse for this DataFile's Dataset not found");
        }
        
        // (4) Roll it all up in a JSON response
        final JsonObjectBuilder dfile_json = Json.createObjectBuilder();
      
        // Dataverse
        dfile_json.add("dv_id", dverse.getId());
        dfile_json.add("dv_name", dverse.getName());
        
        // DatasetVersion Info
        dfile_json.add("dataset_name", dset_version.getTitle());
        dfile_json.add("dataset_description", dset_version.getCitation());
        dfile_json.add("dataset_id", dset_version.getId());
        dfile_json.add("dataset_version_id", dset_version.getVersion());
        
        // DataFile/FileMetaData Info
        dfile_json.add("datafile_id", dfile.getId());
        dfile_json.add("filename", dfile_meta.getLabel());
        dfile_json.add("datafile_label", dfile_meta.getLabel());
        dfile_json.add("datafile_expected_md5_checksum", dfile.getmd5());
        dfile_json.add("filesize", dfile.getFilesize()); 
        dfile_json.add("datafile_type", dfile.getContentType());
        dfile_json.add("created", dfile.getCreateDate().toString());
        
        // DataverseUser Info
        dfile_json.add("dv_user_email", dv_user.getEmail());
        dfile_json.add("dv_username", dv_user.getUserName());
        dfile_json.add("dv_user_id", dv_user.getId());
        
        /*       
        "datafile_download_url": "http://127.0.0.1:8090/media/datafile/2014/06/26/boston_census_blocks_1.zip",
        */
        return okResponse(dfile_json);
 
    }
   
    
    @POST
    @Path("layer-update/{datafile_id}")
    /*
        For WorldMap/GeoConnect Usage
        Create a MayLayerMetadata object for a given Datafile id
        
        !! Does not yet implement permissions/command checks
        !! Change to check for hidden WorldMap key; IP check, etc
    
        Example of jsonLayerData String:
        {
             "layerName": "geonode:boston_census_blocks_zip_cr9"
            , "layerLink": "http://localhost:8000/data/geonode:boston_census_blocks_zip_cr9"
            , "embedMapLink": "http://localhost:8000/maps/embed/?layer=geonode:boston_census_blocks_zip_cr9"
            , "worldmapUsername": "dv_pete"
        }
    */
    public Response updateWorldMapLayerData(String jsonLayerData, @PathParam("datafile_id") Long datafile_id, @QueryParam("key") String apiKey){
        
        // Temp: Check if the user exists
        // Change this to WorldMap API check!!
        DataverseUser dv_user = userSvc.findByUserName(apiKey);
        if (dv_user == null) {
            return errorResponse(Response.Status.UNAUTHORIZED, "Invalid apikey '" + apiKey + "'");
        }
        
        // (1) Attempt to retrieve DataFile indicated by id
        DataFile dfile = dataFileService.find(datafile_id);
        if (dfile==null){
           return errorResponse(Response.Status.NOT_FOUND, "DataFile not found for id: " + datafile_id);
        }
        
        // (2) Parse the json message
        JsonObject json;
        try ( StringReader rdr = new StringReader(jsonLayerData) ) {
            json = Json.createReader(rdr).readObject();
        } catch ( JsonParsingException jpe ) {
            logger.log(Level.SEVERE, "Json: " + jsonLayerData);
            return errorResponse( Response.Status.BAD_REQUEST, "Error parsing Json: " + jpe.getMessage() );
        }
        
        // (3) Make sure the json message has all of the required attributes
        for (String attr : MapLayerMetadataServiceBean.MANDATORY_JSON_FIELDS ){
            if (!json.containsKey(attr)){
                return errorResponse( Response.Status.BAD_REQUEST, "Error parsing Json.  Key not found [" + attr + "]\nRequired keys are: " + MapLayerMetadataServiceBean.MANDATORY_JSON_FIELDS  );
            }
        }
        
        
        MapLayerMetadata mapLayer;
        // See if a MapLayerMetadata already exists
        mapLayer = mapLayerMetadataService.findMetadataByLayerNameAndDatafile(json.getString("layerName"));//, dfile);
        if (mapLayer == null){
            mapLayer = new MapLayerMetadata();
        }

        // Create/Update new MapLayerMetadata object and save it
        mapLayer.setDataFile(dfile);
        mapLayer.setDataset(dfile.getOwner());
        mapLayer.setLayerName(json.getString("layerName"));
        mapLayer.setLayerLink(json.getString("layerLink"));
        mapLayer.setEmbedMapLink(json.getString("embedMapLink"));
        mapLayer.setWorldmapUsername(json.getString("worldmapUsername"));

        //mapLayer.save();
        MapLayerMetadata saved_map_layer = mapLayerMetadataService.save(mapLayer);
        if (saved_map_layer==null){
            logger.log(Level.SEVERE, "Json: " + jsonLayerData);
            return errorResponse( Response.Status.BAD_REQUEST, "Failed to save map layer!  Original JSON: ");
        }
        return okResponse("map layer object saved!");

        
//        return okResponse("In process");
    }
}
