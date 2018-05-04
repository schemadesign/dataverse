package edu.harvard.iq.dataverse.export;

import com.google.auto.service.AutoService;
import edu.harvard.iq.dataverse.DatasetVersion;
import edu.harvard.iq.dataverse.FileMetadata;
import edu.harvard.iq.dataverse.export.spi.Exporter;
import edu.harvard.iq.dataverse.export.ExportException;
import edu.harvard.iq.dataverse.util.SystemConfig;
import edu.harvard.iq.dataverse.util.json.JsonPrinter;
import edu.harvard.iq.dataverse.DataFile;
import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.DatasetField;
import edu.harvard.iq.dataverse.DatasetFieldType;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.logging.Logger;

import javax.ejb.EJB;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;

@AutoService(Exporter.class)
public class OAI_OREExporter implements Exporter {

	private static final Logger logger = Logger.getLogger(OAI_OREExporter.class.getCanonicalName());

	public static final String NAME = "OAI_ORE";

	@Override
	public void exportDataset(DatasetVersion version, JsonObject json, OutputStream outputStream)
			throws ExportException {
		try {

			Dataset dataset = version.getDataset();
			String id = dataset.getGlobalId();
			// json.getString("protocol") + ":" + json.getString("authority") + "/" +
			// json.getString("identifier");
			JsonObjectBuilder aggBuilder = Json.createObjectBuilder();
			List<DatasetField> fields = version.getDatasetFields();
			for (DatasetField field : fields) {
				DatasetFieldType dfType = field.getDatasetFieldType();
				JsonArrayBuilder vals = Json.createArrayBuilder();
				for (String val : field.getValues()) {
					vals.add(val);
				}

				aggBuilder.add(dfType.getTitle(), vals.build());
			}
			
			
			aggBuilder.add("@id", id).add("@type", Json.createArrayBuilder().add("Aggregation").add("Dataset"))
					.add("version", version.getFriendlyVersionNumber())
					.add("datePublished", dataset.getPublicationDateFormattedYYYYMMDD()).add("name", version.getTitle())
					.add("dateModified", version.getLastUpdateTime().toString());

			JsonArrayBuilder aggResArrayBuilder = Json.createArrayBuilder();
			for (FileMetadata fmd : version.getFileMetadatas()) {
				DataFile df = fmd.getDataFile();
				JsonObjectBuilder aggRes = Json.createObjectBuilder().add("description", fmd.getDescription())
						.add("label", fmd.getLabel()) // "label" is the filename
						.add("restricted", fmd.isRestricted()).add("directoryLabel", fmd.getDirectoryLabel())
						.add("version", fmd.getVersion()).add("datasetVersionId", fmd.getDatasetVersion().getId())
						.add("categories", JsonPrinter.getFileCategories(fmd).build())
						.add("@id", df.getId())
						.add("contentType", df.getContentType()).add("filesize", df.getFilesize())
						.add("description", df.getDescription())
						// .add("released", df.isReleased())
						// .add("restricted", df.isRestricted())
						.add("storageIdentifier", df.getStorageIdentifier())
						.add("originalFileFormat", df.getOriginalFileFormat())
						.add("originalFormatLabel", df.getOriginalFormatLabel()).add("UNF", df.getUnf())
						// ---------------------------------------------
						// For file replace: rootDataFileId, previousDataFileId
						// ---------------------------------------------
						.add("rootDataFileId", df.getRootDataFileId())
						.add("previousDataFileId", df.getPreviousDataFileId())
						// ---------------------------------------------
						// Checksum
						// * @todo Should we deprecate "md5" now that it's under
						// * "checksum" (which may also be a SHA-1 rather than an MD5)?
						// ---------------------------------------------
						.add("md5", JsonPrinter.getMd5IfItExists(df.getChecksumType(), df.getChecksumValue()))
						.add("checksum", JsonPrinter
								.getChecksumTypeAndValue(df.getChecksumType(), df.getChecksumValue()).build())
						.add("tabularTags", JsonPrinter.getTabularFileTags(df).build());

				aggResArrayBuilder.add(aggRes.build());
			}

			JsonObject oremap = Json.createObjectBuilder().add("Creation Date", LocalDate.now().toString())
					.add("Creator", ResourceBundle.getBundle("Bundle").getString("institution.name"))
					.add("@type", "ResourceMap")
					.add("@id",
							SystemConfig.getDataverseSiteUrlStatic() + "/api/datasets/export?exporter="
									+ getProviderName() + "&persistentId=" + id)

					.add("describes", aggBuilder.add("aggregates", aggResArrayBuilder.build()).build())

					.add("@context", Json.createArrayBuilder().add("http://www.openarchives.org/ore/0.9/context.json")
							// .add("https://w3id.org/ore/context") - JSON-LD Playground doesn't like this
							// due to redirects
							.add("http://schema.org")
							.add(Json.createObjectBuilder().add("Creation Date", "http://purl.org/dc/terms/created")
									.add("Creator", "http://purl.org/dc/elements/1.1/creator")
									.add("Has Version", "http://purl.org/dc/terms/hasVersion")
									.add("persistentUrl", "http://purl.org/dc/elements/1.1/identifier")))
					.build();
			logger.info(oremap.toString());

			/*
			 * metadataDefsMap.toMap ++ Map(
			 * 
			 * "Rights" -> Json.toJson("http://purl.org/dc/terms/rights"), "Date" ->
			 * Json.toJson("http://purl.org/dc/elements/1.1/date"),
			 * 
			 * "Label" -> Json.toJson("http://www.w3.org/2000/01/rdf-schema#label"),
			 * "Location" -> Json.toJson( "http://sead-data.net/terms/generatedAt"),
			 * "Description" -> Json.toJson("http://purl.org/dc/elements/1.1/description"),
			 * "Keyword"->
			 * Json.toJson("http://www.holygoat.co.uk/owl/redwood/0.1/tags/taggedWithTag"),
			 * "Title" -> Json.toJson("http://purl.org/dc/elements/1.1/title"),
			 * 
			 * "Contact" -> Json.toJson("http://sead-data.net/terms/contact"), "name" ->
			 * Json.toJson("http://sead-data.net/terms/name"), "email" ->
			 * Json.toJson("http://schema.org/Person/email"), "Publication Date" ->
			 * Json.toJson("http://purl.org/dc/terms/issued"), "Spatial Reference" ->
			 * Json.toJson( Map(
			 * 
			 * "@id" -> Json.toJson("tag:tupeloproject.org,2006:/2.0/gis/hasGeoPoint"),
			 * "Longitude" -> Json.toJson("http://www.w3.org/2003/01/geo/wgs84_pos#long"),
			 * "Latitude" -> Json.toJson("http://www.w3.org/2003/01/geo/wgs84_pos#lat"),
			 * "Altitude" -> Json.toJson("http://www.w3.org/2003/01/geo/wgs84_pos#alt")
			 * 
			 * )), "Comment" -> Json.toJson(Map( "comment_body" ->
			 * Json.toJson("http://purl.org/dc/elements/1.1/description"), "comment_date" ->
			 * Json.toJson("http://purl.org/dc/elements/1.1/date"), "@id" ->
			 * Json.toJson("http://cet.ncsa.uiuc.edu/2007/annotation/hasAnnotation"),
			 * "comment_author" -> Json.toJson("http://purl.org/dc/elements/1.1/creator")
			 * )), "Has Description" -> Json.toJson("http://purl.org/dc/terms/description"),
			 * "Bibliographic citation" ->
			 * Json.toJson("http://purl.org/dc/terms/bibliographicCitation"), "Published In"
			 * -> Json.toJson("http://purl.org/dc/terms/isPartOf"), "Publisher" ->
			 * Json.toJson("http://purl.org/dc/terms/publisher"), "External Identifier" ->
			 * Json.toJson("http://purl.org/dc/terms/identifier"), "references" ->
			 * Json.toJson("http://purl.org/dc/terms/references"), "Is Version Of" ->
			 * Json.toJson("http://purl.org/dc/terms/isVersionOf"), "Has Part" ->
			 * Json.toJson("http://purl.org/dc/terms/hasPart"), "Size" ->
			 * Json.toJson("tag:tupeloproject.org,2006:/2.0/files/length"), "Mimetype" ->
			 * Json.toJson("http://purl.org/dc/elements/1.1/format"), "SHA512 Hash" ->
			 * Json.toJson("http://sead-data.net/terms/hasSHA512Digest"),
			 * "Dataset Description" ->
			 * Json.toJson("http://sead-data.net/terms/datasetdescription"),
			 * "Publishing Project" ->
			 * Json.toJson("http://sead-data.net/terms/publishingProject"), "License" ->
			 * Json.toJson("http://purl.org/dc/terms/license") ) )
			 */
			try {
				outputStream.write(oremap.toString().getBytes("UTF8"));
				outputStream.flush();
			} catch (IOException ex) {
				logger.info("IOException calling outputStream.write: " + ex);
			}
		} catch (Exception e) {
			logger.severe(e.getMessage());
			e.printStackTrace();
		}
		/*
		 * 
		 * curations.get(curationId) match { case Some(c) => {
		 * 
		 * val https = controllers.Utils.https(request) val key =
		 * play.api.Play.configuration.getString("commKey").getOrElse("") val filesJson
		 * = curations.getCurationFiles(curations.getAllCurationFileIds(c.id)).map {
		 * file =>
		 * 
		 * var fileMetadata = scala.collection.mutable.Map.empty[String, JsValue]
		 * metadatas.getMetadataByAttachTo(ResourceRef(ResourceRef.curationFile,
		 * file.id)).filter(_.creator.typeOfAgent == "cat:user").map { item =>
		 * fileMetadata = fileMetadata ++
		 * curationObjectController.buildMetadataMap(item.content) } val size =
		 * files.get(file.fileId) match { case Some(f) => f.length case None => 0 }
		 * 
		 * var tempMap = Map( "Identifier" -> Json.toJson("urn:uuid:"+file.id), "@id" ->
		 * Json.toJson("urn:uuid:"+file.id), "Creation Date" ->
		 * Json.toJson(format.format(file.uploadDate)), "Label" ->
		 * Json.toJson(file.filename), "Title" -> Json.toJson(file.filename),
		 * "Uploaded By" -> Json.toJson(userService.findById(file.author.id).map ( usr
		 * => Json.toJson(file.author.fullName + ": " +
		 * api.routes.Users.findById(usr.id).absoluteURL(https)))), "Size" ->
		 * Json.toJson(size), "Mimetype" -> Json.toJson(file.contentType),
		 * "Publication Date" -> Json.toJson(""), "External Identifier" ->
		 * Json.toJson(""), "SHA512 Hash" -> Json.toJson(file.sha512), "@type" ->
		 * Json.toJson(Seq("AggregatedResource", "http://cet.ncsa.uiuc.edu/2015/File")),
		 * "Is Version Of" ->
		 * Json.toJson(controllers.routes.Files.file(file.fileId).absoluteURL(https) +
		 * "?key=" + key), "similarTo" ->
		 * Json.toJson(api.routes.Files.download(file.fileId).absoluteURL(https) +
		 * "?key=" + key)
		 * 
		 * ) if(file.tags.size > 0 ) { tempMap = tempMap ++ Map("Keyword" ->
		 * Json.toJson(file.tags.map(_.name))) }
		 * 
		 * tempMap ++ fileMetadata
		 * 
		 * } val foldersJson =
		 * curations.getCurationFolders(curations.getAllCurationFolderIds(c.id)).map {
		 * folder =>
		 * 
		 * val hasPart = folder.files.map(file=>"urn:uuid:"+file) ++
		 * folder.folders.map(fd => "urn:uuid:"+fd) val tempMap = Map( "Creation Date"
		 * -> Json.toJson(format.format(folder.created)), "Rights" ->
		 * Json.toJson(c.datasets(0).licenseData.m_licenseText), "Identifier" ->
		 * Json.toJson("urn:uuid:"+folder.id), "License" ->
		 * Json.toJson(c.datasets(0).licenseData.m_licenseText), "Label" ->
		 * Json.toJson(folder.name), "Title" -> Json.toJson(folder.displayName),
		 * "Uploaded By" -> Json.toJson(folder.author.fullName + ": " +
		 * api.routes.Users.findById(folder.author.id).absoluteURL(https)), "@id" ->
		 * Json.toJson("urn:uuid:"+folder.id), "@type" ->
		 * Json.toJson(Seq("AggregatedResource",
		 * "http://cet.ncsa.uiuc.edu/2016/Folder")), "Is Version Of" ->
		 * Json.toJson(controllers.routes.Datasets.dataset(c.datasets(0).id).absoluteURL
		 * (https) +"#folderId=" +folder.folderId), "Has Part" -> Json.toJson(hasPart) )
		 * tempMap
		 * 
		 * } val hasPart = c.files.map(file => "urn:uuid:"+file) ++ c.folders.map(folder
		 * => "urn:uuid:"+folder) var commentsByDataset =
		 * comments.findCommentsByDatasetId(c.datasets(0).id)
		 * curations.getCurationFiles(curations.getAllCurationFileIds(c.id)).map { file
		 * => commentsByDataset ++= comments.findCommentsByFileId(file.fileId)
		 * sections.findByFileId(UUID(file.fileId.toString)).map { section =>
		 * commentsByDataset ++= comments.findCommentsBySectionId(section.id) } }
		 * commentsByDataset = commentsByDataset.sortBy(_.posted) val commentsJson =
		 * commentsByDataset.map { comm => Json.toJson(Map( "comment_body" ->
		 * Json.toJson(comm.text), "comment_date" ->
		 * Json.toJson(format.format(comm.posted)), "Identifier" ->
		 * Json.toJson("urn:uuid:"+comm.id), "comment_author" ->
		 * Json.toJson(userService.findById(comm.author.id).map ( usr =>
		 * Json.toJson(usr.fullName + ": " +
		 * api.routes.Users.findById(usr.id).absoluteURL(https)))) )) } var metadataList
		 * = scala.collection.mutable.ListBuffer.empty[MetadataPair] var metadataKeys =
		 * Set.empty[String]
		 * metadatas.getMetadataByAttachTo(ResourceRef(ResourceRef.curationObject,
		 * c.id)).filter(_.creator.typeOfAgent == "cat:user").map { item => for((key,
		 * value) <- curationObjectController.buildMetadataMap(item.content)) {
		 * metadataList += MetadataPair(key, value) metadataKeys += key } } var
		 * metadataJson = scala.collection.mutable.Map.empty[String, JsValue] for(key <-
		 * metadataKeys) { metadataJson = metadataJson ++ Map(key ->
		 * Json.toJson(metadataList.filter(_.label == key).map{item =>
		 * item.content}toList)) } val metadataDefsMap =
		 * scala.collection.mutable.Map.empty[String, JsValue] for(md <-
		 * metadatas.getDefinitions(Some(c.space))) { metadataDefsMap((md.json\
		 * "label").asOpt[String].getOrElse("").toString()) = Json.toJson((md.json \
		 * "uri").asOpt[String].getOrElse("")) } if(metadataJson.contains("Creator")) {
		 * val value = c.creators ++ metadataList.filter(_.label == "Creator").map{item
		 * => item.content.as[String]}.toList metadataJson = metadataJson ++
		 * Map("Creator" -> Json.toJson(value)) } else { metadataJson = metadataJson ++
		 * Map("Creator" -> Json.toJson(c.creators)) }
		 * if(!metadataDefsMap.contains("Creator")){ metadataDefsMap("Creator") =
		 * Json.toJson(Map("@id" -> "http://purl.org/dc/terms/creator", "@container" ->
		 * "@list")) } val publicationDate = c.publishedDate match { case None => ""
		 * case Some(p) => format.format(c.created) }
		 * if(metadataJson.contains("Abstract")) { val value = List(c.description) ++
		 * metadataList.filter(_.label == "Abstract").map{item =>
		 * item.content.as[String]} metadataJson = metadataJson ++ Map("Abstract" ->
		 * Json.toJson(value)) } else { metadataJson = metadataJson ++ Map("Abstract" ->
		 * Json.toJson(c.description)) } if(!metadataDefsMap.contains("Abstract")){
		 * metadataDefsMap("Abstract") =
		 * Json.toJson("http://purl.org/dc/terms/abstract") } var aggregation =
		 * metadataJson if(commentsJson.size > 0) { aggregation = metadataJson ++ Map(
		 * "Comment" -> Json.toJson(JsArray(commentsJson))) } if(c.datasets(0).tags.size
		 * > 0) { aggregation = aggregation ++ Map("Keyword" -> Json.toJson(
		 * Json.toJson(c.datasets(0).tags.map(_.name)) )) } var parsedValue = Map(
		 * "@context" -> Json.toJson(Seq( Json.toJson("https://w3id.org/ore/context"),
		 * Json.toJson( metadataDefsMap.toMap ++ Map( "Identifier" ->
		 * Json.toJson("http://purl.org/dc/elements/1.1/identifier"), "Rights" ->
		 * Json.toJson("http://purl.org/dc/terms/rights"), "Date" ->
		 * Json.toJson("http://purl.org/dc/elements/1.1/date"), "Creation Date" ->
		 * Json.toJson("http://purl.org/dc/terms/created"), "Label" ->
		 * Json.toJson("http://www.w3.org/2000/01/rdf-schema#label"), "Location" ->
		 * Json.toJson( "http://sead-data.net/terms/generatedAt"), "Description" ->
		 * Json.toJson("http://purl.org/dc/elements/1.1/description"), "Keyword"->
		 * Json.toJson("http://www.holygoat.co.uk/owl/redwood/0.1/tags/taggedWithTag"),
		 * "Title" -> Json.toJson("http://purl.org/dc/elements/1.1/title"),
		 * "Uploaded By" -> Json.toJson("http://purl.org/dc/elements/1.1/creator"),
		 * "Contact" -> Json.toJson("http://sead-data.net/terms/contact"), "name" ->
		 * Json.toJson("http://sead-data.net/terms/name"), "email" ->
		 * Json.toJson("http://schema.org/Person/email"), "Publication Date" ->
		 * Json.toJson("http://purl.org/dc/terms/issued"), "Spatial Reference" ->
		 * Json.toJson( Map(
		 * 
		 * "@id" -> Json.toJson("tag:tupeloproject.org,2006:/2.0/gis/hasGeoPoint"),
		 * "Longitude" -> Json.toJson("http://www.w3.org/2003/01/geo/wgs84_pos#long"),
		 * "Latitude" -> Json.toJson("http://www.w3.org/2003/01/geo/wgs84_pos#lat"),
		 * "Altitude" -> Json.toJson("http://www.w3.org/2003/01/geo/wgs84_pos#alt")
		 * 
		 * )), "Comment" -> Json.toJson(Map( "comment_body" ->
		 * Json.toJson("http://purl.org/dc/elements/1.1/description"), "comment_date" ->
		 * Json.toJson("http://purl.org/dc/elements/1.1/date"), "@id" ->
		 * Json.toJson("http://cet.ncsa.uiuc.edu/2007/annotation/hasAnnotation"),
		 * "comment_author" -> Json.toJson("http://purl.org/dc/elements/1.1/creator")
		 * )), "Has Description" -> Json.toJson("http://purl.org/dc/terms/description"),
		 * "Bibliographic citation" ->
		 * Json.toJson("http://purl.org/dc/terms/bibliographicCitation"), "Published In"
		 * -> Json.toJson("http://purl.org/dc/terms/isPartOf"), "Publisher" ->
		 * Json.toJson("http://purl.org/dc/terms/publisher"), "External Identifier" ->
		 * Json.toJson("http://purl.org/dc/terms/identifier"), "references" ->
		 * Json.toJson("http://purl.org/dc/terms/references"), "Is Version Of" ->
		 * Json.toJson("http://purl.org/dc/terms/isVersionOf"), "Has Part" ->
		 * Json.toJson("http://purl.org/dc/terms/hasPart"), "Size" ->
		 * Json.toJson("tag:tupeloproject.org,2006:/2.0/files/length"), "Mimetype" ->
		 * Json.toJson("http://purl.org/dc/elements/1.1/format"), "SHA512 Hash" ->
		 * Json.toJson("http://sead-data.net/terms/hasSHA512Digest"),
		 * "Dataset Description" ->
		 * Json.toJson("http://sead-data.net/terms/datasetdescription"),
		 * "Publishing Project" ->
		 * Json.toJson("http://sead-data.net/terms/publishingProject"), "License" ->
		 * Json.toJson("http://purl.org/dc/terms/license") ) )
		 * 
		 * )), "Rights" -> Json.toJson("CC0"), "describes" -> Json.toJson(
		 * aggregation.toMap ++ Map( "Identifier" -> Json.toJson("urn:uuid:" + c.id),
		 * "Creation Date" -> Json.toJson(format.format(c.created)), "Label" ->
		 * Json.toJson(c.name), "Title" -> Json.toJson(c.name), "Dataset Description" ->
		 * Json.toJson(c.description), "Uploaded By" ->
		 * Json.toJson(userService.findById(c.author.id).map ( usr =>
		 * Json.toJson(usr.fullName + ": " +
		 * api.routes.Users.findById(usr.id).absoluteURL(https)))), "Publication Date"
		 * -> Json.toJson(publicationDate), "Published In" -> Json.toJson(""),
		 * "External Identifier" -> Json.toJson(""), "Proposed for publication" ->
		 * Json.toJson("true"), "@id" ->
		 * Json.toJson(api.routes.CurationObjects.getCurationObjectOre(c.id).absoluteURL
		 * (https) + "#aggregation"), "@type" -> Json.toJson(Seq("Aggregation",
		 * "http://cet.ncsa.uiuc.edu/2015/Dataset")), "Is Version Of" ->
		 * Json.toJson(controllers.routes.Datasets.dataset(c.datasets(0).id).absoluteURL
		 * (https)), "similarTo" ->
		 * Json.toJson(controllers.routes.Datasets.dataset(c.datasets(0).id).absoluteURL
		 * (https)), "aggregates" -> Json.toJson(filesJson ++ foldersJson.toList),
		 * "Has Part" -> Json.toJson(hasPart), "Publishing Project"->
		 * Json.toJson(controllers.routes.Spaces.getSpace(c.space).absoluteURL(https))
		 * )), "Creation Date" -> Json.toJson(format.format(c.created)), "Uploaded By"
		 * -> Json.toJson(userService.findById(c.author.id).map ( usr =>
		 * Json.toJson(usr.fullName + ": " +
		 * api.routes.Users.findById(usr.id).absoluteURL(https)))), "@type" ->
		 * Json.toJson("ResourceMap"), "@id" ->
		 * Json.toJson(api.routes.CurationObjects.getCurationObjectOre(curationId).
		 * absoluteURL(https)) )
		 * 
		 * Ok(Json.toJson(parsedValue)) } case None =>
		 * InternalServerError("Publication Request not Found"); }
		 */
	}

	@Override
	public String getProviderName() {
		return NAME;
	}

	@Override
	public String getDisplayName() {
		return ResourceBundle.getBundle("Bundle").getString("dataset.exportBtn.itemLabel.oai_ore") != null
				? ResourceBundle.getBundle("Bundle").getString("dataset.exportBtn.itemLabel.oai_ore")
				: "OAI_ORE";
	}

	@Override
	public Boolean isXMLFormat() {
		return false;
	}

	@Override
	public Boolean isHarvestable() {
		// Defer harvesting because the current effort was estimated as a "2":
		// https://github.com/IQSS/dataverse/issues/3700
		return false;
	}

	@Override
	public Boolean isAvailableToUsers() {
		return true;
	}

	@Override
	public String getXMLNameSpace() throws ExportException {
		throw new ExportException(OAI_OREExporter.class.getSimpleName() + ": not an XML format.");
	}

	@Override
	public String getXMLSchemaLocation() throws ExportException {
		throw new ExportException(OAI_OREExporter.class.getSimpleName() + ": not an XML format.");
	}

	@Override
	public String getXMLSchemaVersion() throws ExportException {
		throw new ExportException(SchemaDotOrgExporter.class.getSimpleName() + ": not an XML format.");
	}

	@Override
	public void setParam(String name, Object value) {
		// this exporter doesn't need/doesn't currently take any parameters
	}

}
