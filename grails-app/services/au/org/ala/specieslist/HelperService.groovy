/*
 * Copyright (C) 2022 Atlas of Living Australia
 * All Rights Reserved.
 *
 * The contents of this file are subject to the Mozilla Public
 * License Version 1.1 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 */

package au.org.ala.specieslist

import au.org.ala.names.ws.api.NameUsageMatch
//import au.org.ala.names.ws.api.SearchStyle
import com.opencsv.CSVReader
import com.sun.management.OperatingSystemMXBean
import grails.gorm.transactions.NotTransactional
import grails.gorm.transactions.Transactional
import net.bytebuddy.matcher.NameMatcher
import org.hibernate.Session

import java.lang.management.ManagementFactory
import java.time.LocalDateTime
import java.sql.Timestamp
import groovy.time.*
import groovyx.net.http.ContentType
import groovyx.net.http.HTTPBuilder
import groovyx.net.http.Method
import org.apache.commons.io.input.BOMInputStream
import org.apache.commons.lang.StringUtils
import org.grails.web.json.JSONArray
import org.nibor.autolink.*
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.apache.http.util.EntityUtils

import javax.annotation.PostConstruct
import java.util.regex.Matcher
import java.util.regex.Pattern

import org.hibernate.ScrollMode

/**
 * Provides all the services for the species list webapp.  It may be necessary to break this into
 * multiple services if it grows too large
 */
@Transactional
class HelperService {

    MessageSource messageSource

    def grailsApplication

    def localAuthService, authService, userDetailsService, webService

    def sessionFactory

    BieService bieService

    NameExplorerService nameExplorerService

    ColumnMatchingService columnMatchingService

    Integer BATCH_SIZE
    /**
    * Completed
    * Running
    * Failed
    * Abnormal: If the status is still running,but the last update time is 30 min before. It will be set as abnormal,
    * since it should update every 1-5 minutes in processing.  The Abnormal status may be caused by server shutdown.
     * */
    enum Status {
        RUNNING,
        COMPLETED,
        FAILED,
        ABORT
    }


    // Only permit URLs for added safety
    private final LinkExtractor extractor = LinkExtractor.builder().linkTypes(EnumSet.of(LinkType.URL)).build()

    @PostConstruct
    init(){
        BATCH_SIZE = Integer.parseInt((grailsApplication?.config?.batchSize?:200).toString())
     }

    /**
     * Adds a data resource to the collectory for this species list
     * @param username
     * @param description
     * @return
     */
    def addDataResourceForList(map) {
        if(grailsApplication.config.getProperty('collectory.enableSync', Boolean, false)){
            def postUrl = grailsApplication.config.collectory.baseURL + "/ws/dataResource"
            def http = new HTTPBuilder(postUrl)
            http.setHeaders([Authorization: "Bearer ${webService.getTokenService().getAuthToken(false)}"])
            http.getClient().getParams().setParameter("http.socket.timeout", new Integer(5000))
            Map jsonBody = createJsonForNewDataResource(map)
            log.debug(jsonBody?.toString())
            String newDataResource = null
            try {
               http.request(Method.POST) {
                    uri.path = postUrl
                    body = jsonBody
                    requestContentType = ContentType.JSON
                    response.success = { resp ->
                        log.info("Created a collectory entry for the species list.  ${resp.status}")
                        newDataResource = resp.headers['location'].getValue()
                    }
                    response.failure = { resp ->
                        log.error("Unable to create a collectory entry for the species list.  ${resp.status}")
                    }
                }

            } catch (ex){
                log.error("Unable to create a collectory entry for the species list. ", ex)
            }

            newDataResource

        } else {
           //return a dummy URL
          grailsApplication.config.collectory.baseURL + "/tmp/drt" + System.currentTimeMillis()
        }
    }

    def deleteDataResourceForList(drId) {
        if(grailsApplication.config.getProperty('collectory.enableSync', Boolean, false)){
            def deleteUrl = grailsApplication.config.collectory.baseURL +"/ws/dataResource/" + drId
            def http = new HTTPBuilder(deleteUrl)
            http.getClient().getParams().setParameter("http.socket.timeout", new Integer(5000))
            http.setHeaders([Authorization: "Bearer ${webService.getTokenService().getAuthToken(false)}"])

            try {
                http.request(Method.DELETE) {
                    requestContentType = ContentType.JSON
                    response.success = { resp ->
                        def result = (resp.getEntity() != null ? EntityUtils.toString(resp.getEntity()) : "")
                        log.info("${drId} has been deleted from ${grailsApplication.config.collectory.baseURL} with ${result}")
                    }
                    response.failure = { resp ->
                        log.error("Delete request for ${drId} failed with status ${resp.status}")
                    }
                }
            } catch (ex){
                log.error("Unable to delete a collectory entry for the species list.", ex)
            }
        }
    }

    def updateDataResourceForList(drId, map) {
        if (grailsApplication.config.getProperty('collectory.enableSync', Boolean, false)){
            def postUrl = grailsApplication.config.collectory.baseURL + "/ws/dataResource/" + drId
            def http = new HTTPBuilder(postUrl)
            http.setHeaders([Authorization: "Bearer ${webService.getTokenService().getAuthToken(false)}"])
            http.getClient().getParams().setParameter("http.socket.timeout", new Integer(5000))
            def jsonBody = createJsonForNewDataResource(map)
            try {
               http.request(Method.POST) {
                    uri.path = postUrl
                    body = jsonBody
                    requestContentType = ContentType.JSON
                    response.success = { resp ->
                        log.info("Updated the collectory entry for the species list ${drId}.  ${resp.status}")
                    }
                    response.failure = { resp ->
                        log.error("Unable to update the collectory entry for the species list ${drId}.  ${resp.status}")
                    }
               }
            } catch(ex) {
                log.error("Unable to update a collectory entry for the species list.",ex)
            }
        } else {
           //return a dummy URL
          grailsApplication.config.collectory.baseURL + "/tmp/drt" + System.currentTimeMillis()
        }
    }

    Map createJsonForNewDataResource(map){
        map.resourceType = "species-list"
        if (map['signedUser']) {
           map.user = map['signedUser'].userId
           map.firstName = map['signedUser'].firstName?:""
           map.lastName = map['signedUser'].lastName?:""
        } else {
            //What are those for?
            map.user = 'Species list upload'
            map.firstName = localAuthService.firstname() ?: ""
            map.lastName = localAuthService.surname() ?: ""
        }
        map
    }

    def uploadFile(druid, uploadedFile){
        if(druid){
            def destDir = new File(grailsApplication.config.bie.download + File.separator + druid + File.separator)
            destDir.mkdirs()
            def destFile = new File(destDir, "species_list.csv")
            uploadedFile.transferTo(destFile)
            destFile.absolutePath
        }
    }

    def getCSVReaderForText(String raw, String separator) {
        new CSVReader(new StringReader(raw), separator.charAt(0))
    }

    def getCSVReaderForCSVFileUpload(file, char separator) {
        new CSVReader(new InputStreamReader(new BOMInputStream(file.getInputStream())), separator)
    }

    def getSeparator(String raw) {
        String firstLine = raw.indexOf("\n") > 0 ? raw.substring(0, raw.indexOf("\n")) : raw

        int tabs = firstLine.count("\t")
        int commas = firstLine.count(",")

        tabs > commas ? '\t' : ','
    }

    def parseValues(String[] processedHeader, CSVReader reader, String sep)throws Exception{
        def sciIdx = indexOfName(processedHeader)
        if(sciIdx>=0){
            //now lets determine the possible values
            String[] nextLine
            Map map =[:]
            while ((nextLine = reader.readNext()) != null) {
                  nextLine.eachWithIndex {v,i ->
                      if(i != sciIdx){
                          if(i>=processedHeader.length)
                              throw new Exception("Row length does NOT match header length. Problematic row is " + nextLine.join(sep))
                          def set =map.get(processedHeader[i], [] as Set)
                          if(v != processedHeader[i])
                            set.add(v)
                      }
                  }
            }
            return map;
        } else {
            null
        }
    }

    def indexOfName(String[] processedHeader){
        processedHeader.findIndexOf {it == "scientific name" || it == "vernacular name" || it == "ambiguous name"}
    }

    /**
     * determines what the header should be based on the data supplied
     * @param header
     */
    def parseData(String[] header){
        def hasName = false
        def unknowni =1
        def headerResponse = header.collect{
            if(findAcceptedLsidByScientificName(it)){
                hasName = true
                "scientific name"
            } else if(findAcceptedLsidByCommonName(it)){
                hasName = true
                "vernacular name"
            } else {
                "UNKNOWN" + (unknowni++)
            }
        }
        [header: headerResponse, nameFound: hasName]
    }

    def parseRow(List row) {
        def ret = []

        String item
        row.each {String it ->
            item = parseUrls(it)
            ret << item
        }

        ret
    }

    private String parseUrls(String item) {
//        String ret = null
//
//        Iterable<LinkSpan> links = extractor.extractLinks(item)
//        if (links) {
//            ret = Autolink.renderLinks(item, links, {LinkSpan ls, CharSequence text, StringBuilder sb ->
//                sb.append("<a href=\"")
//                sb.append(text, ls.beginIndex, ls.endIndex);
//                sb.append("\">")
//                sb.append(text, ls.beginIndex, ls.endIndex)
//                sb.append("</a>")
//            } as LinkRenderer)
//        }
//        else {
//            ret = item
//        }
//
//        ret
        return item
    }

    private boolean hasValidData(Map map, String [] nextLine) {
        boolean result = false
        map.each { key, value ->
            if (StringUtils.isNotBlank(nextLine[value])){
                result =  true
            }
        }
        result
    }

    def vocabPattern = ~ / ?([A-Za-z0-9]*): ?([A-Z a-z0-9']*)(?:,|$)/

    //Adds the associated vocabulary
    def addVocab(druid, vocab, kvpmap){
        if(vocab){
            vocab.each{
                //parse the values of format <key1>: <vocabValue1>,<key2>: <vocab2>
                def matcher =vocabPattern.matcher(it.value)
                //pattern match based on the the groups first item is the complete match
                matcher.each{match, value, vocabValue ->
                    def key = it.key.replaceFirst("vocab_","")
                    kvpmap.put(key+"|"+value, new SpeciesListKVP(key: key, value: value, dataResourceUid: druid, vocabValue: vocabValue))
                }
            }
        }
    }

    def loadSpeciesListFromJSON(Map json, String druid, boolean replace = true) {
        SpeciesList speciesList = SpeciesList.findByDataResourceUid(druid) ?: new SpeciesList(json)

        if (replace) {
            // updating an existing list
            if (speciesList.dataResourceUid) {
                // assume new list of species will replace existing one (no updates allowed for now)
                speciesList.items?.clear()

                // update the list of editors (comma separated list of email addresses)
                if (json?.editors) {
                    // merge lists and remove duplicates
                    speciesList.editors = (speciesList.editors + json.editors.tokenize(',')).unique()
                }
                if (json?.listName) {
                    speciesList.listName = json.listName // always update the list name
                }
            } else {
                // create a new list
                speciesList.setDataResourceUid(druid)
            }

            if (speciesList.username && !speciesList.userId) {
                def currentUser = userDetailsService.getCurrentUserDetails()
                if(currentUser){
                    log.warn("Current user found. Updating species list user details")
                    speciesList.userId = currentUser.getUserId()
                    speciesList.firstName = currentUser.getFirstName()
                    speciesList.surname  = currentUser.getLastName()
                }


            }
        }

        List guidList = []
        // version 1 of this operation supports list items as a comma-separated string
        // version 2 of this operation supports list items as structured JSON elements with KVPs
        if (isSpeciesListJsonVersion1(json)) {
            guidList = loadSpeciesListItemsFromJsonV1(json, speciesList, druid)
        } else if (isSpeciesListJsonVersion2(json)) {
            guidList = loadSpeciesListItemsFromJsonV2(json, speciesList, druid)
        } else {
            throw new UnsupportedOperationException("Unsupported data structure")
        }
        speciesList.lastUploaded = new Date()

        if (!speciesList.validate()) {
            log.error(speciesList.errors.allErrors?.toString())
        }

        List sli = speciesList.getItems().toList()
        matchCommonNamesForSpeciesListItems(sli)
        speciesList.lastMatched = new Date()

        speciesList.save(flush: true, failOnError: true)

        [speciesList: speciesList, speciesGuids: guidList]
    }

    private static boolean isSpeciesListJsonVersion1(Map json) {
        // version 1 of this operation supports list items as a comma-separated string
        json.listItems in String
    }

    private loadSpeciesListItemsFromJsonV1(Map json, SpeciesList speciesList, String druid) {
        assert json.listItems, "Cannot create a Species List with no items"

        List items = json.listItems.split(",")

        List guidList = []
        items.eachWithIndex { item, i ->
            SpeciesListItem sli = new SpeciesListItem(dataResourceUid: druid, rawScientificName: item, itemOrder: i)
            matchNameToSpeciesListItem(sli.rawScientificName, sli, speciesList)
            speciesList.addToItems(sli)
            guidList.push (sli.guid)
        }
        guidList
    }

    private static boolean isSpeciesListJsonVersion2(Map json) {
        // version 2 of this operation supports list items as structured JSON elements with KVPs - i.e. a JSON Array
        json.listItems in JSONArray
    }

    private loadSpeciesListItemsFromJsonV2(Map json, SpeciesList speciesList, String druid) {
        assert json.listItems, "Cannot create a Species List with no items"

        List speciesGuidKvp = []
        Map kvpMap = [:]
        List items = json.listItems
        items.eachWithIndex { item, i ->
            SpeciesListItem sli = new SpeciesListItem(dataResourceUid: druid, rawScientificName: item.itemName,
                    itemOrder: i)

            item.kvpValues?.eachWithIndex { k, j ->
                SpeciesListKVP kvp = new SpeciesListKVP(value: k.value, key: k.key, itemOrder: j, dataResourceUid:
                        druid)
                sli.addToKvpValues(kvp)
                kvpMap[k.key] = k.value
            }
            matchNameToSpeciesListItem(sli.rawScientificName, sli, speciesList)

            speciesList.addToItems(sli)

            speciesGuidKvp.push (["guid": sli.guid, "kvps": kvpMap])
        }
        speciesGuidKvp
    }

    def loadSpeciesListFromCSV(CSVReader reader, druid, listname, ListType listType, description, listUrl, listWkt,
                               Boolean isBIE, Boolean isSDS, Boolean isAuthoritative, Boolean isThreatened, Boolean isInvasive,
                               Boolean isPrivate, String region, String authority, String category,
                               String generalisation, String sdsType, Boolean looseSearch, /*SearchStyle searchStyle,*/ String[] header, Map vocabs) {
        log.debug("Loading species list " + druid + " " + listname + " " + description + " " + listUrl + " " + header + " " + vocabs)

        def kvpmap = [:]
        addVocab(druid,vocabs,kvpmap)
        //attempt to retrieve an existing list first
        SpeciesList sl = SpeciesList.findByDataResourceUid(druid)?:new SpeciesList()
        if (sl.dataResourceUid){
            sl.items.clear()
        }
        sl.listName = listname
        sl.dataResourceUid=druid
        sl.username = localAuthService.email() ?: "info@ala.org.au"
        sl.userId = authService.userId ?: 2729
        sl.firstName = localAuthService.firstname()
        sl.surname = localAuthService.surname()
        sl.description = description
        sl.url = listUrl
        sl.wkt = listWkt
        sl.listType = listType
        sl.region = region
        sl.authority = authority
        sl.category = category
        sl.generalisation = generalisation
        sl.sdsType = sdsType
        sl.isBIE = isBIE
        sl.isSDS = isSDS
        sl.isAuthoritative = isAuthoritative
        sl.isThreatened = isThreatened
        sl.isInvasive = isInvasive
        sl.isPrivate = isPrivate
        sl.looseSearch = looseSearch
        //sl.searchStyle = searchStyle
        sl.lastUploaded = new Date()
        sl.lastMatched = new Date()
        String [] nextLine
        boolean checkedHeader = false
        Map termIdx = columnMatchingService.getTermAndIndex(header)
        int itemCount = 0
        int totalCount = 0
        log.info('Loading records from CSV/Excel...')
        String[] rawHeaders = []
        while ((nextLine = reader.readNext()) != null) {
            totalCount++
            if(!checkedHeader){
                checkedHeader = true
                rawHeaders = nextLine
                // only read next line if current line is a header line
                if(columnMatchingService.getTermAndIndex(nextLine).size() > 0) {
                    nextLine = reader.readNext()
                }
            }

            if(nextLine.length > 0 && termIdx.size() > 0 && hasValidData(termIdx, nextLine)){
                itemCount++
                sl.addToItems(insertSpeciesItem(nextLine, druid, termIdx, header, rawHeaders,kvpmap, itemCount, sl))
            }
            if (totalCount % 500 == 0) {
                log.info("${totalCount} records have been processed.")
            }
        }
        log.info("Completed ${totalCount} records in total")
        if(!sl.validate()){
            log.error(sl.errors.allErrors?.toString())
        }

        log.info("Matching ${totalCount} records....")
        List sli = sl.getItems()?.toList()
        matchCommonNamesForSpeciesListItems(sli)
        log.info("Saving ${totalCount} records....")
        sl.save()
        log.info("${totalCount} records saved")
        [totalRecords: totalCount, successfulItems: itemCount]
    }

    @Deprecated
    def loadSpeciesListFromFile(listname, druid, filename, boolean useHeader, header,vocabs){

        CSVReader reader = new CSVReader(new FileReader(filename),',' as char)
        header = header ?: reader.readNext()

        int speciesValueIdx = columnMatchingService.getSpeciesIndex(header)
        int count =0
        String [] nextLine
        def kvpmap =[:]
        //add vocab
        addVocab(druid,vocabs,kvpmap)
        SpeciesList sl = new SpeciesList()
        sl.listName = listname
        sl.dataResourceUid=druid
        sl.username = localAuthService.email()
        sl.firstName = localAuthService.firstname()
        sl.surname = localAuthService.surname()
        sl.lastUploaded = new Date()
        sl.lastMatched = new Date()
        while ((nextLine = reader.readNext()) != null) {
            if(org.apache.commons.lang.StringUtils.isNotBlank(nextLine)){
                sl.addToItems(insertSpeciesItem(nextLine, druid, speciesValueIdx, header, kvpmap, sl))
                count++
            }

        }

        List sli = sl.getItems().toList()
        matchCommonNamesForSpeciesListItems(sli)

        sl.save()
    }

    @Deprecated
    def insertSpeciesItem(String[] values, druid, int speciesIdx, Object[] header, map, int order, SpeciesList sl){
        values = parseRow(values as List)
        log.debug("Inserting " + values.toArrayString())

        SpeciesListItem sli = new SpeciesListItem()
        sli.dataResourceUid =druid
        sli.rawScientificName = speciesIdx > -1 ? values[speciesIdx] : null
        sli.itemOrder = order
         int i = 0
        header.each {
            if(i != speciesIdx && values.length > i && values[i]?.trim()){
                SpeciesListKVP kvp = map.get(it.toString()+"|"+values[i], new SpeciesListKVP(key: it.toString(), value: values[i], dataResourceUid: druid))
                if  (kvp.itemOrder == null) {
                    kvp.itemOrder = i
                }
                sli.addToKvpValues(kvp)
            }
            i++
        }
        matchNameToSpeciesListItem(sli.rawScientificName, sli, sl)
        sli
    }

    /**
     *
     * @param values
     * @param druid
     * @param termIndex
     * @param header
     * @param rawHeaders  Use the original header when store values into KVP, especial for vernacular name variants
     * @param map
     * @param order
     * @param sl
     * @return
     */
    def insertSpeciesItem(String[] values, String druid, Map termIndex, Object[] header, String[] rawHeaders, Map map, int order, SpeciesList sl){
        values = parseRow(values as List)
        log.debug("Inserting " + values.toArrayString())

        SpeciesListItem sli = new SpeciesListItem()
        sli.dataResourceUid = druid
        sli.rawScientificName = termIndex.containsKey(QueryService.RAW_SCIENTIFIC_NAME) ? values[termIndex[QueryService.RAW_SCIENTIFIC_NAME]] : null
        sli.itemOrder = order

        int i = 0

        def excludedFields = [QueryService.RAW_SCIENTIFIC_NAME, QueryService.COMMON_NAME]

        // vernacular name is converted to 'commonName' in termIndex
        // check commonNameColumns in config ->  commonname,common,vernacular,vernacularname will be interpreted as "commonName"
        // We want to store the original header value as key
        if (termIndex.containsKey(QueryService.COMMON_NAME)) {
            def kValue =  values[termIndex.get(QueryService.COMMON_NAME)] ? values[termIndex.get(QueryService.COMMON_NAME)] : ""
            def commanName = QueryService.COMMON_NAME
            def possibleNames  = grailsApplication.config.getProperty("commonNameColumns")?.split(",")
            def orginalName = rawHeaders.find{it -> possibleNames*.toLowerCase().contains(it.toLowerCase())}
            SpeciesListKVP kvp = new SpeciesListKVP(key: orginalName ? orginalName : commanName, value: kValue, dataResourceUid: druid)
            if  (kvp.itemOrder == null) {
                kvp.itemOrder = 0
            }
            sli.addToKvpValues(kvp)
        }

        header.each {
            if (values.length > i && values[i]?.trim()) {
                if (!excludedFields.contains(termIndex.find{it.value == i}?.key)) {
                    def kValue = values[i] ? values[i] : ""
                    SpeciesListKVP kvp = map.get(it.toString()+"|"+values[i], new SpeciesListKVP(key: it.toString(), value: kValue, dataResourceUid: druid))
                    if  (kvp.itemOrder == null) {
                        kvp.itemOrder = i
                    }
                    sli.addToKvpValues(kvp)
                }
            }
            i++
        }

        matchNameToSpeciesListItem(sli.rawScientificName, sli, sl)
        sli
    }

    def  matchNameToSpeciesListItem(String name, SpeciesListItem sli, SpeciesList sl){
        // First match using all available data
        NameUsageMatch match = nameExplorerService.find(sli, sl)
        if (!match || !match.success) {
            match = nameExplorerService.searchForRecordByCommonName(sli.rawScientificName)
        }
        if (!match || !match.success) {
            match = nameExplorerService.searchForRecordByLsid(sli.rawScientificName)
        }
        if(match && match.success){
            // Not necessary
            sli.matchedName = match.scientificName
            sli.commonName = StringUtils.capitalize(match.vernacularName)
            //Legacy: 'family, kingdom' field is used by group query for facades
            sli.family = match.getFamily()
            sli.kingdom = match.getKingdom()
            sli.guid = match.taxonConceptID
            MatchedSpecies newMS = new MatchedSpecies()
            newMS.taxonConceptID  = match.taxonConceptID
            newMS.scientificName = match.scientificName
            newMS.scientificNameAuthorship = match.scientificNameAuthorship
            newMS.vernacularName = StringUtils.capitalize(match.vernacularName)
            newMS.kingdom = match.kingdom
            newMS.phylum = match.phylum
            newMS.taxonClass = match.classs
            newMS.taxonOrder = match.order
            newMS.family = match.family
            newMS.genus = match.genus
            newMS.taxonRank = match.rank
            sli.matchedSpecies = newMS
        } else {
            sli.guid = null
            sli.matchedName = null
            sli.author = null
            sli.matchedSpecies = null
            //reset image
            sli.imageUrl = null
        }
        sli.save()
    }

    void matchAll(List searchBatch, SpeciesList speciesList) {
        List<NameUsageMatch> matches = nameExplorerService.findAll(searchBatch, speciesList);
        matches.eachWithIndex {  NameUsageMatch match, Integer index ->
            SpeciesListItem sli = searchBatch[index]
            if (match && !match.success) {
                match = nameExplorerService.searchForRecordByCommonName(sli.rawScientificName)
            }
            if (match && !match.success) {
                match = nameExplorerService.searchForRecordByLsid(sli.rawScientificName)
            }
            if (match && match.success) {
                sli.guid = match.getTaxonConceptID()
                sli.matchedName = match.getScientificName()
                sli.author = match.getScientificNameAuthorship()
                sli.commonName = StringUtils.capitalize(match.getVernacularName())
                sli.family = match.getFamily()
                sli.kingdom = match.getKingdom()

                MatchedSpecies newMS = new MatchedSpecies()
                newMS.taxonConceptID  = match.taxonConceptID
                newMS.scientificName = match.scientificName
                newMS.scientificNameAuthorship = match.scientificNameAuthorship
                newMS.vernacularName = StringUtils.capitalize(match.vernacularName)
                newMS.kingdom = match.kingdom
                newMS.phylum = match.phylum
                newMS.taxonClass = match.classs
                newMS.taxonOrder = match.order
                newMS.family = match.family
                newMS.genus = match.genus
                newMS.taxonRank = match.rank
                newMS.lastUpdated = new Date()

                sli.matchedSpecies = newMS
            } else {
                sli.guid = null
                sli.matchedName = null
                sli.author = null
                sli.matchedSpecies = null
                sli.lastUpdated = new Date()
                //reset image
                sli.imageUrl = null
                log.debug("Unable to match species list item - ${sli.rawScientificName}")
            }
            sli.save()
        }
    }

    def findAcceptedLsidByCommonName(commonName) {
        String lsid = null
        try {
            lsid = nameExplorerService.searchForLsidByCommonName(commonName)
        } catch (Exception e) {
            log.error("findAcceptedLsidByCommonName -  " + e.getMessage())
        }
        lsid
    }

    def findAcceptedLsidByScientificName(scientificName) {
        String lsid = null
        try {
            lsid = nameExplorerService.searchForAcceptedLsidByScientificName(scientificName);
        } catch (Exception e) {
            log.error(e.getMessage())
        }
        lsid
    }

    // JSON response is returned as the unconverted model with the appropriate
    // content-type. The JSON conversion is handled in the filter. This allows
    // for universal JSONP support.
    def asJson(model, response)  {
        response.setContentType("application/json")
        model
    }

    /**
     * finds common name for a guid and saves it to the database. This is done in batches.
     * @param slItems
     */
    void matchCommonNamesForSpeciesListItems(List slItems){
        Integer batchSize = BATCH_SIZE;
        List guidBatch = [], sliBatch = []
        slItems?.each{ SpeciesListItem sli ->
            if(guidBatch.size() < batchSize){
                if(sli.guid){
                    guidBatch.push(sli.guid)
                    sliBatch.push(sli)
                }
            } else {
                getCommonNamesAndUpdateRecords(sliBatch, guidBatch)
                guidBatch = []
                sliBatch = []
            }
        }

        if(guidBatch.size()){
            getCommonNamesAndUpdateRecords(sliBatch, guidBatch)
        }
    }

    @Transactional
    def createRecord (params) {
        def sl = SpeciesList.get(params.id)
        log.debug "params = " + params
        if (!sl && params.druid) {
            sl = SpeciesList.findByDataResourceUid(params.druid)
        }

        if (!params.rawScientificName) {
            return [text: "Missing required field: rawScientificName", status: 400]
        }
        else if (sl) {
            def keys = SpeciesListKVP.executeQuery("select distinct key from SpeciesListKVP where dataResourceUid=:dataResourceUid", [dataResourceUid: sl.dataResourceUid])
            log.debug "keys = " + keys
            def sli = new SpeciesListItem(dataResourceUid: sl.dataResourceUid, rawScientificName: params.rawScientificName, itemOrder: sl.items.size() + 1)
            matchNameToSpeciesListItem(sli.rawScientificName, sli, sl)

            keys.each { key ->
                log.debug "key: " + key + " has value: " + params[key]
                def value = params[key]
                def itemOrder = params["itemOrder_${key}"] ?: 0
                if (value) {
                    def newKvp = SpeciesListKVP.findByDataResourceUidAndKeyAndValue(sl.dataResourceUid, key, value)
                    if (!newKvp) {
                        log.debug "Couldn't find an existing KVP, so creating a new one..."
                        newKvp = new SpeciesListKVP(dataResourceUid: sli.dataResourceUid, key: key, value: params[key], SpeciesListItem: sli, itemOrder: itemOrder );
                        newKvp.save()
                    }
                    sli.addToKvpValues(newKvp)
                }
            }

            sl.addToItems(sli)

            if (!sl.validate()) {
                def message = "Could not update SpeciesList with new item: ${sli.rawScientificName} - " + sl.errors.allErrors
                log.error message
                return [text: message, status: 500]
            }
            else if (sl.save()) {
                // find common name and save it
                matchCommonNamesForSpeciesListItems([sli])
                sl.lastMatched = new Date()
                sl.lastUploaded = new Date()
                sl.save(flush: true)

                sli.save()

                // Commented out as we would like to keep species list generic
                /*   def preferredSpeciesImageListName = grailsApplication.config.ala.preferred.species.name
                   if (sl.listName == preferredSpeciesImageListName) {
                       helperService.syncBieImage (sli, params.imageId)
                   }*/
                def msg = messageSource.getMessage('public.lists.view.table.edit.messages', [] as Object[], 'Default Message', LocaleContextHolder.locale)
                return[text: msg, status: 200, data: ["species_guid": sli.guid]]
            }
            else {
                def message = "Could not create SpeciesListItem: ${sli.rawScientificName} - " + sl.errors.allErrors
                return [text: message, status: 500]
            }
        }
        else {
            def message = "${message(code: 'default.not.found.message', args: [message(code: 'speciesList.label', default: 'Species List'), params.id])}"
            return [text: message, status: 404]
        }
    }

    /**
     * This function finds small image url for a guid and updates the corresponding SpeciesListItem record
     * @param sliBatch - list of SpeciesListItems
     * @param guidBatch - list of GUID strings
     */
    void getCommonNamesAndUpdateRecords(List sliBatch, List guidBatch) {
        try{
            List speciesProfiles = bieService.bulkSpeciesLookupWithGuids(guidBatch)
            speciesProfiles?.eachWithIndex { Map profile, index ->
                SpeciesListItem slItem = sliBatch[index]
                if (profile) {
                    slItem.imageUrl = profile.smallImageUrl
                }
            }
        } catch (Exception e){
            log.error("an exception occurred during rematching: ${e.message}");
            log.error(e.stackTrace?.toString())
        }
    }

    /**
     * Rematching status:
     * Completed
     * Running
     * Failed
     * Abnormal: If the status is still running,but the last update time is 30 min before. It will be set as abnormal,
     * since it should update every 1-5 minutes in processing.  The Abnormal status may be caused by server shutdown.
     * @return
     */
    def queryRematchingProcess(){
        boolean processing = RematchLog.findByStatus(Status.RUNNING.toString()) ? true : false
        def logs = RematchLog.list(max: 10, sort: "id", order: "desc")
        Map result = ["processing": processing, "history": logs.collect { it.toMap() }]
        return result
    }

    def deleteRematchLog(long id) {
        def rematchLog = RematchLog.findById(id)
        rematchLog?.delete(flush: true)
    }



    /**
     * Rematch species list items for a species list
     * @param speciesList
     * @param reset if true, all matched species for the list will be removed
     * @return
     */
    @NotTransactional
    def rematchList(SpeciesList speciesList, boolean reset = false) {

        if (reset) {
            MatchedSpecies.withTransaction {
                SpeciesListItem.executeUpdate("update SpeciesListItem set matchedSpecies = null where data_resource_uid = :listDRId", [listDRId: speciesList.dataResourceUid])
                MatchedSpecies.executeUpdate("delete from MatchedSpecies where id in (select matchedSpecies from SpeciesListItem where data_resource_uid = :listDRId)", [listDRId: speciesList.dataResourceUid])
            }
        }

        String listDRId = speciesList.dataResourceUid
        Integer totalRows = SpeciesListItem.countByDataResourceUid(listDRId)
        if (totalRows <= 0) {
            return [status: 0, message: "Ignored. No species in the list ${listDRId}"]
        }

        def message=[status: 0, message: "Rematching ${totalRows} species in the list ${listDRId}"]
        log.info("Rematching ${totalRows} species in the list ${listDRId}")

        def startProcessing = new Date()
        Session session = sessionFactory.openSession()
        session.beginTransaction()
        session.doWork { connection ->
            // Increase session timeout
            connection.createStatement().executeUpdate("SET SESSION wait_timeout = 28800")
            connection.createStatement().executeUpdate("SET SESSION interactive_timeout = 28800")
            connection.createStatement().executeUpdate("SET SESSION net_read_timeout = 600")
            connection.createStatement().executeUpdate("SET SESSION net_write_timeout = 600")
        }

        def scrollableResults

        try{
            List speciesItems = new ArrayList<SpeciesListItem>()
            def hql = "SELECT sli FROM SpeciesListItem  sli left join fetch sli.matchedSpecies WHERE sli.dataResourceUid = :listDRId "
            def query = session.createQuery(hql)
            query.setParameter("listDRId", listDRId)
            //query.setParameter("startId", startId)
            scrollableResults = query.scroll(ScrollMode.FORWARD_ONLY)
            int count = 0
            def startReading = new Date()
            while (scrollableResults.next()) {
                def species = (SpeciesListItem) scrollableResults.get(0)
                speciesItems.add(species)
                if (++count % BATCH_SIZE == 0) {
                    log.debug("Reading ${count} / ${totalRows} took ${ TimeCategory.minus(new Date(), startReading)}")
                    rematchSpeciesInList(session, speciesList, speciesItems)
                    speciesItems.clear()
                    startReading = new Date()
                }
            }
            //Process the rest bit of species items < 500 (BATCH_SIZE)
            if (speciesItems.size() > 0) {
                rematchSpeciesInList(session, speciesList, speciesItems)
            }

            scrollableResults.close()
            String msg = "${listDRId} [ ${totalRows} ] completed, time cost : ${TimeCategory.minus(new Date(), startProcessing)}"
            log.info(msg)

            SpeciesList speciesListToUpdate = session.get(SpeciesList.class, speciesList.id)
            if (speciesListToUpdate != null) {
                speciesListToUpdate.lastMatched = new Date();
                session.update(speciesListToUpdate)
                session.flush()
            }

            message = [status: 0, message: msg]
        } catch (Exception e) {
            session.getTransaction().rollback()
            message = [status: 1, message: "Failed in rematching the list ${listDRId}"]
            log.error("Error in rematching:" + e.message)
        } finally {
            scrollableResults.close()
            if (session.isOpen()) {
                session.close()
            }
        }
        message
    }

    /**
     * Update matched species for a species list item after comparing with the name match
     * @param sli
     * @param nameMatch
     */
    void updateMatchedSpecies(Session session, SpeciesListItem sli, NameUsageMatch nameMatch) {
        if (sli.matchedSpecies) {
            if (sli.matchedSpecies.taxonConceptID != nameMatch.taxonConceptID ||
                    !sli.matchedSpecies.scientificName?.equalsIgnoreCase(nameMatch.scientificName) ||
                    !sli.matchedSpecies.scientificNameAuthorship?.equalsIgnoreCase(nameMatch.scientificNameAuthorship) ||
                    !sli.matchedSpecies.vernacularName?.equalsIgnoreCase(nameMatch.vernacularName) ||
                    !sli.matchedSpecies.kingdom?.equalsIgnoreCase(nameMatch.kingdom) ||
                    !sli.matchedSpecies.phylum?.equalsIgnoreCase(nameMatch.phylum) ||
                    !sli.matchedSpecies.taxonClass?.equalsIgnoreCase(nameMatch.classs) ||
                    !sli.matchedSpecies.taxonOrder?.equalsIgnoreCase(nameMatch.order) ||
                    !sli.matchedSpecies.family?.equalsIgnoreCase(nameMatch.family) ||
                    !sli.matchedSpecies.genus?.equalsIgnoreCase(nameMatch.genus) ||
                    !sli.matchedSpecies.taxonRank?.equalsIgnoreCase(nameMatch.rank)) {
                sli.matchedSpecies.taxonConceptID = nameMatch.taxonConceptID
                sli.matchedSpecies.scientificName = nameMatch.scientificName
                sli.matchedSpecies.scientificNameAuthorship = nameMatch.scientificNameAuthorship
                sli.matchedSpecies.vernacularName = nameMatch.vernacularName
                sli.matchedSpecies.kingdom = nameMatch.kingdom
                sli.matchedSpecies.phylum = nameMatch.phylum
                sli.matchedSpecies.taxonClass = nameMatch.classs
                sli.matchedSpecies.taxonOrder = nameMatch.order
                sli.matchedSpecies.family = nameMatch.family
                sli.matchedSpecies.genus = nameMatch.genus
                sli.matchedSpecies.taxonRank = nameMatch.rank

                sli.matchedSpecies.lastUpdated = new Date()
            }
        } else {
            MatchedSpecies matchedSpecies = new MatchedSpecies()

            matchedSpecies.taxonConceptID = nameMatch.taxonConceptID
            matchedSpecies.scientificName = nameMatch.scientificName
            matchedSpecies.scientificNameAuthorship = nameMatch.scientificNameAuthorship
            matchedSpecies.vernacularName = nameMatch.vernacularName
            matchedSpecies.kingdom = nameMatch.kingdom
            matchedSpecies.phylum = nameMatch.phylum
            matchedSpecies.taxonClass = nameMatch.classs
            matchedSpecies.taxonOrder = nameMatch.order
            matchedSpecies.family = nameMatch.family
            matchedSpecies.genus = nameMatch.genus
            matchedSpecies.taxonRank = nameMatch.rank
            matchedSpecies.lastUpdated = new Date()
            // For generating id of the matched species
            session.saveOrUpdate(matchedSpecies)
            sli.matchedSpecies = matchedSpecies
        }

    }

    /**
     * Rematch a list of species in a species list
     *
     * @param speciesItems
     * @return
     */

    def rematchSpeciesInList(Session session, SpeciesList list, List<SpeciesListItem> speciesItems) {
        Date startRematching = new Date()
        List<String> guids = speciesItems.collect { it.guid }

        speciesItems.each { SpeciesListItem item ->
            String rawName = removeHtmlTag(item.rawScientificName)
            if (rawName && rawName.length() > 0) {
                if (rawName != item.rawScientificName) {
                    item.rawScientificName = rawName
                }
            } else {
                item.guid = null
                // remove from the list of waiting process?
            }
        }

        Date startNES = new Date()
        List<NameUsageMatch> matches = nameExplorerService.findAll(speciesItems, list)
        log.debug("Time cost on nameExlorerService:  ${TimeCategory.minus(new Date(), startNES)}")

        Date startNESRecord = new Date()
        matches.eachWithIndex { NameUsageMatch match, Integer index ->
            SpeciesListItem sli = speciesItems[index]
            if (match && !match.success) {
                match = nameExplorerService.searchForRecordByCommonName(sli.rawScientificName)
            }
            if (match && !match.success) {
                match = nameExplorerService.searchForRecordByLsid(sli.rawScientificName)
            }
            if (match && match.success) {
                sli.guid = match.getTaxonConceptID()
                sli.matchedName = match.getScientificName()
                sli.author = match.getScientificNameAuthorship()
                sli.commonName = StringUtils.capitalize(match.getVernacularName())
                sli.family = match.getFamily()
                sli.kingdom = match.getKingdom()
                updateMatchedSpecies(session, sli, match)
            } else {
                sli.guid = null
                sli.matchedName = null
                sli.author = null
                sli.matchedSpecies = null
                sli.lastUpdated = new Date()
                //reset image
                sli.imageUrl = null
                log.debug("Unable to match species list item - ${sli.rawScientificName}")
            }
        }
        log.debug("Time cost of Each Records on nameExlorerService:  ${TimeCategory.minus(new Date(), startNESRecord)}")

        Date startBIESearch = new Date()

        List speciesProfiles = bieService.bulkSpeciesLookupWithGuids(guids)
        speciesProfiles?.eachWithIndex { Map profile, i ->
            SpeciesListItem slItem = speciesItems[i]
            if (profile) {
                slItem.imageUrl = profile.smallImageUrl
            }
        }
        log.debug("Time cost of Each Records on nameExlorerService:  ${TimeCategory.minus(new Date(), startBIESearch)}")
        log.debug("Rematching took ${ TimeCategory.minus(new Date(), startRematching)}")

        //Save to DB
        Date updatingDB = new Date()
        speciesItems.each { SpeciesListItem it ->
            session.saveOrUpdate(it)
        }
        session.flush()
        session.clear()
        log.debug("Saving to DB took ${ TimeCategory.minus(new Date(), updatingDB)}")
    }


    String removeHtmlTag(String value) {
        Pattern pattern = Pattern.compile("<a[^>]*>(.*?)</a>")
        Matcher matcher = pattern.matcher(value)

        if (matcher.find()) {
            return matcher.group(1)
        }
        return value
    }
}
