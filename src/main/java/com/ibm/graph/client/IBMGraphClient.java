package com.ibm.graph.client;

import com.ibm.graph.client.exception.GraphException;
import com.ibm.graph.client.exception.GraphClientException;
import com.ibm.graph.client.response.GraphResponse;
import com.ibm.graph.client.schema.Schema;
import com.ibm.graph.client.response.ResultSet;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.*;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.wink.json4j.JSONArray;
import org.apache.wink.json4j.JSONObject;
import org.apache.wink.json4j.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by markwatson on 11/8/16.
 */
public class IBMGraphClient {

    private String apiURL;
    private String username;
    private String password;
    private String baseURL;
    private String gdsTokenAuth;
    private String graphId;

    private static Logger logger =  LoggerFactory.getLogger(IBMGraphClient.class);

    /**
     * Constructor. Creates a IBMGraphClient that provides access to the IBM Graph service
     * instance identified by the first IBM Graph entry in the VCAP_SERVICES environment
     * variable.
     *
     * @throws GraphClientException if VCAP_SERVICES is not defined or not valid
     */
    public IBMGraphClient() throws GraphClientException {
        Map envs = System.getenv();
        if (envs.get("VCAP_SERVICES") != null) {
            String graphServiceName = "IBM Graph";
            try {
                JSONObject vcapSvcs = new JSONObject(envs.get("VCAP_SERVICES").toString());
                if (!vcapSvcs.isNull(graphServiceName)) {
                    JSONObject creds = vcapSvcs.getJSONArray(graphServiceName).getJSONObject(0).getJSONObject("credentials");
                    this.apiURL = creds.getString("apiURL");
                    this.username = creds.getString("username");
                    this.password = creds.getString("password");
                    this.init();
                }
            }
            catch(JSONException jsonex) {
                throw new GraphClientException("IBMGraphClient cannot be initialized. Environment variable VCAP_SERVICES is invalid: " + jsonex.getMessage());    
            }
        }
        else {
            throw new GraphClientException("IBMGraphClient cannot be initialized. Environment variable VCAP_SERVICES is not defined.");
        }
    }

    /**
     * Constructor. Creates a IBMGraphClient that provides access to the IBM Graph service
     * using the provided credentials.
     * 
     * @param apiURL IBM Graph service instance API URL
     * @param username IBM Graph instance user name
     * @param password IBM Graph instance password
     * @throws GraphClientException if the credentials are missing or invalid 
     */
    public IBMGraphClient(String apiURL, String username, String password) throws GraphClientException {
        this.apiURL = apiURL;
        this.username = username;
        this.password = password;
        this.init();
    }

    private void init() throws GraphClientException {
        if((this.apiURL == null) || (this.username == null) || (this.password == null)) {
            throw new GraphClientException("IBMGraphClient cannot be initialized. apiURL: " + this.apiURL + " username: " + this.username + " password: " + this.password);
        }
        // TODO error checking
        this.baseURL = this.apiURL.substring(0, this.apiURL.lastIndexOf('/'));
        this.graphId = this.apiURL.substring(this.apiURL.lastIndexOf('/') + 1);
    }

    private void initSession() throws GraphClientException, GraphException {
        if (this.baseURL == null) {
            throw new GraphClientException("Invalid configuration. Please specify a valid apiURL, username, and password.");
        }
        String basicAuthHeader = "Basic " + Base64.getEncoder().encodeToString((this.username + ":" + this.password).getBytes());
        HttpGet httpGet = new HttpGet(this.baseURL + "/_session");
        httpGet.setHeader("Authorization", basicAuthHeader);
        CloseableHttpClient httpclient = HttpClients.createDefault();
        CloseableHttpResponse httpResponse = null;
        try {
            httpResponse = httpclient.execute(httpGet);
            HttpEntity httpEntity = httpResponse.getEntity();
            String content = EntityUtils.toString(httpEntity);
            EntityUtils.consume(httpEntity);
            JSONObject jsonContent = new JSONObject(content);
            this.gdsTokenAuth = "gds-token " + jsonContent.getString("gds-token");
        }
        catch(Exception ex) {
            logger.error("IBMGraphClient cannot establish a session with the IBM Graph service instance.", ex);
            throw new GraphClientException("IBMGraphClient cannot establish a session with the IBM Graph service instance: " + ex.getMessage());
        }
        finally {
            try { 
                    if(httpResponse != null) {
                     httpResponse.close();   
                    }
            }
            catch(IOException ioe) {}
        }
    }

    /*
     * ----------------------------------------------------------------
     * Graph methods:
     *  - getGraphId
     *  - setGraph
     *  - getGraphs
     *  - createGraph
     *  - deleteGraph
     * ----------------------------------------------------------------     
     */

    /**
     * Returns the name of the graph that this client is connected to.
     * @return id of the graph that this client is connected to.
     */
    public String getGraphId() {
        return this.graphId;
    }

    /**
     * Switches to the graph identified by graphId.
     * @param graphId name of the graph on which the client will operate on in the future
     * @throws GraphException if no graph with the specified graphId exists
     * @throws GraphClientException if the client encountered a fatal error
     * @throws IllegalArgumentException if graphId is null or empty
     */
    public void setGraph(String graphId) throws GraphException, GraphClientException, IllegalArgumentException {

        if((graphId == null) || (graphId.trim().length() == 0))
            throw new IllegalArgumentException("Parameter graphId is null or empty.");

        if(ArrayUtils.contains(getGraphs(), graphId)) {
            this.graphId = graphId;
            this.apiURL = String.format("%s/%s",this.baseURL,this.graphId);
        }
        else {
            throw new GraphException("No graph with name " + graphId + " is defined.");
        }
    }

    /**
     * Returns list of graphs that are defined in this IBM Graph service instance
     * @return An array of strings, identifying graphs that can be switched to.
     * @throws GraphException if IBM Graph returned an error
     * @throws GraphClientException if the client encountered a fatal error
     * @see com.ibm.graph.client.IBMGraphClient#setGraph
     */
    public String[] getGraphs() throws GraphException, GraphClientException {

        try {
            String url = this.baseURL + "/_graphs";

            GraphResponse response = this.doHttpGet(url);
            if(response.getHTTPStatus().isSuccessResponse()) {
                JSONObject result = response.getResultSet().getResultAsJSONObject(0);
                // {"graphs":["1...3","1","g","zzz"]}
                if(result.has("graphs")) {
                    JSONArray data = result.getJSONArray("graphs");    
                    List<String> graphIds = new ArrayList<>();
                    if (data.length() > 0) {
                        for(int i=0; i<data.length(); i++) {
                            graphIds.add(data.getString(i));
                        }
                    }
                    return graphIds.toArray(new String[0]);
                }
            }
            else {
                // IBM Graph returned an error
                throw new GraphException(response.getGraphStatus(), response.getHTTPStatus());
            }
        }
        catch(GraphClientException gcex) {
            throw gcex;
        }
        catch(Exception ex) {
            logger.error("Error getting list of graphs: ", ex);
            throw new GraphException("Error getting list of graphs: " + ex.getMessage());
        }
    }

    /**
     * Creates a new graph with a unique id.
     * @return The graph id
     * @throws GraphException if the graph cannot be created
     * @throws GraphClientException if the client encountered a fatal error
     */
    public String createGraph() throws GraphException, GraphClientException {
        return this.createGraph(null);
    }

    /**
     * Creates a new graph identified by the specified graphId. Valid graphIds must 
     * follow this pattern /^[a-z0-9][a-z0-9_-]*$/ 
     * If no graphId is provided, a unique id will be assigned.
     * @param graphId unique graph id
     * @return The assigned graph id
     * @throws GraphException if the graph cannot be created
     * @throws GraphClientException if the client encountered a fatal error
     */
    public String createGraph(String graphId) throws GraphException, GraphClientException {
        logger.trace("createGraph " + graphId);
        try {
            String url = String.format("%s/_graphs",this.baseURL);
            if (graphId != null && graphId.trim().length() > 0) {
                url += String.format("/%s",graphId.trim());
            }

            GraphResponse response = this.doHttpPost(null, url);
            if(response.getHTTPStatus().isSuccessResponse()) {
                JSONObject result = response.getResultSet().getResultAsJSONObject(0);
                // response: {"graphId":"1...3","dbUrl":"https://...3"}
                if(result.has("graphId")) {
                    return result.getString("graphId");
                }
                else {
                    // the response does not contain a graph name; raise error
                    throw new GraphClientException("IBM Graph response with HTTP code \"" + response.getHTTPStatus().getStatusCode() + "\" and message body " + response.getResponseBody() + "\" cannot be processed. No graph id was returned.");
                }
            }
            else {
                // IBM Graph returned an error
                throw new GraphException(response.getGraphStatus(), response.getHTTPStatus());
            }
        }
        catch(GraphClientException gcex) {
            throw gcex;
        }
        catch(GraphException gex) {
            throw gex;
        }
        catch(Exception ex) {
            throw new GraphClientException("An exception was caught trying to create a graph:" + ex.getMessage(), ex);               
        }
    }

    /**
     * Deletes the graph identified by graphId.
     * @param graphId id of the graph to be deleted
     * @return boolean true if the graph was deleted, false if it couldn't be found
     * @throws GraphException if the specified graph cannot be deleted
     * @throws GraphClientException if the client encountered a fatal error
     * @throws IllegalArgumentException if graphId is null or empty     
     */
    public boolean deleteGraph(String graphId) throws GraphClientException, GraphException, IllegalArgumentException {

        if((graphId == null) || (graphId.trim().length() == 0))
            throw new IllegalArgumentException("Parameter graphId is null or empty.");

        try {
            String url = String.format("%s/_graphs/%s",this.baseURL,graphId.trim());

            GraphResponse response = this.doHttpDelete(url);
            // sample success response: {"data":{}}
            if(response.getHTTPStatus().isSuccessResponse()) {
                return true;
            }
            else {
                if(response.getHTTPStatus().getStatusCode() == 404) {
                    // graph not found. don't treat this as an error
                    return false;
                }
                   
                // unknown status code; raise error
                throw new GraphClientException("The graph could not be deleted. IBM Graph responded with HTTP code \"" + response.getHTTPStatus().getStatusCode() + "\" and message body " + response.getResponseBody() + "\".");
            }
        }
        catch(GraphClientException gcex) {
            throw gcex;
        }
        catch(Exception ex) {
            throw new GraphClientException("An exception was caught trying to delete a graph:" + ex.getMessage(), ex);               
        }
    }

    /*
     * --------------------------------------------------------------------------
     * Schema methods: https://ibm-graph-docs.ng.bluemix.net/api.html#schema-apis
     * A graph schema is defined by its edge labels, vertex labels, property keys, 
     * and indexes. Schema APIs describe the labels and properties that vertices 
     * and edges can have and the indexes that are used to query them. Schemas are 
     * immutable. If multiple POST requests are made to the /schema endpoint, new 
     * elements are processed but any repeated elements are ignored - even if their 
     * values have changed.
     *  - getSchema
     *  - saveSchema
     * --------------------------------------------------------------------------
     */

    /**
     * Returns the schema for the current graph
     * @return com.ibm.graph.client.schema.Schema object 
     * @throws GraphException if IBM Graph returned an error
     * @throws GraphClientException if the client encountered a fatal error
     */
    public Schema getSchema() throws GraphException, GraphClientException {
        try {
            String url = this.apiURL + "/schema";

            GraphResponse response = this.doHttpGet(url);
            if(response.getHTTPStatus().isSuccessResponse()) {
                if(response.getResultSet().hasResults())
                    return Schema.fromJSONObject(response.getResultSet().getResultAsJSONObject(0));
                else {
                    throw new GraphClientException("IBM Graph responded with HTTP code \"" + response.getHTTPStatus().getStatusCode() + "\" and message body " + response.getResponseBody() + "\" cannot be processed.");
                }
            }    
        }
        catch(GraphClientException gcex) {
            throw gcex;
        }
        catch(Exception ex) {
            throw new GraphClientException("An exception was caught trying to fetch the schema:" + ex.getMessage(), ex); 
        }
    }

    /**
     * Creates or updates (ignoring existing properties/indexes) the schema.
     * @param schema definition
     * @return com.ibm.graph.client.schema.Schema the schema
     * @throws GraphException if an error occurred on the server
     * @throws GraphClientException if an error occurred on the client
     * @throws IllegalArgumentException if schema is null
     * @see com.ibm.graph.client.schema.Schema
     */
    public Schema saveSchema(Schema schema) throws GraphException, GraphClientException, IllegalArgumentException {
        if(schema == null)
            throw new IllegalArgumentException("Parameter schema is null.");
        try {
            String url = this.apiURL + "/schema";

            GraphResponse response = this.doHttpPost(schema, url);
            if(response.getHTTPStatus().isSuccessResponse()) {
                if(response.getResultSet().hasResults())
                    return Schema.fromJSONObject(response.getResultSet().getResultAsJSONObject(0));
                else {
                    throw new GraphClientException("IBM Graph response with HTTP code \"" + response.getHTTPStatus().getStatusCode() + "\" and message body " + response.getResponseBody() + "\" cannot be processed.");
                }
            }
            else {
                if(response.getHTTPStatus().isClientErrorResponse()) {
                    throw new GraphException("The schema could not be saved.", response.getGraphStatus(), response.getHTTPStatus());
                }
                else 
                    throw new GraphClientException("The schema could not be saved. IBM Graph responded with HTTP code \"" + response.getHTTPStatus().getStatusCode() + "\" and message body " + response.getResponseBody() + "\".");
            }               
        }  
        catch(GraphClientException gcex) {
            throw gcex;
        }
        catch(GraphException gex) {
            throw gex;
        }
        catch(Exception ex) {
            throw new GraphClientException("An exception was caught trying to save the schema:" + ex.getMessage(), ex); 
        }
    }

    /*
     * ----------------------------------------------------------------
     * Index methods:
     *  - deleteIndex
     * ----------------------------------------------------------------     
     */

    /**
     * Deletes an index in the current graph.
     * @param indexName name of an existing index
     * @return true if indexName was removed
     * @throws IllegalArgumentException if schema is null or an empty string     
     * @throws GraphException if the index could not be deleted 
     */
    public boolean deleteIndex(String indexName) throws GraphException, IllegalArgumentException {
       if((indexName == null) || (indexName.trim().length() == 0))
            throw new IllegalArgumentException("Parameter indexName is null or empty.");
        try {
            String url = this.apiURL + "/index/" + indexName;

            GraphResponse response = this.doHttpDelete(url);
            if(response.getHTTPStatus().isSuccessResponse()) {
                if(response.getResultSet().hasResults())
                    return response.getResultSet().getResultAsBoolean(0).booleanValue();
                else {
                    throw new GraphClientException("The index was deleted. IBM Graph responded with HTTP code \"" + response.getHTTPStatus().getStatusCode() + "\" and message body " + response.getResponseBody() + "\".");
                }
            }
            else {
                throw new GraphClientException("The index could not be deleted. IBM Graph responded with HTTP code \"" + response.getHTTPStatus().getStatusCode() + "\" and message body " + response.getResponseBody() + "\".");
            }
        }  
        catch(GraphClientException gcex) {
            throw gcex;
        }
        catch(Exception ex) {
            throw new GraphClientException("An exception was caught trying to delete the index named " + indexName + " :" + ex.getMessage(), ex); 
        }            
    }

    /*
     * ----------------------------------------------------------------
     * Vertex methods:
     *  - getVertex
     *  - addVertex
     *  - updateVertex
     *  - deleteVertex
     * ----------------------------------------------------------------     
     */

    /**
     * Gets the vertex identified by id from the current graph.
     * @param id vertex id
     * @return com.ibm.graph.client.Vertex or null if no vertex with the specified id exists
     * @throws GraphException if an error (other than "not found") occurred
     * @throws GraphClientException if an error (other than "not found") occurred     
     * @throws IllegalArgumentException id is null 
     */
    public Vertex getVertex(Object id) throws GraphException, GraphClientException, IllegalArgumentException {
        if(id == null)
            throw new IllegalArgumentException("id parameter is missing");
        try {
            String url = String.format("%s/vertices/%s",this.apiURL,id);
            ResultSet rs = new ResultSet(this.doHttpGet(url));
            if(rs.hasResults()) {
                return rs.getResultAsVertex(0);
            }
            else {
                // check status to determine whether no result was returned because the vertex was not found
                if("NotFoundError".equalsIgnoreCase(rs.getStatusCode()))
                    return null;

                // Notify caller that we cannnot determine why the edge could not be retrieved; manual troubleshooting is required
                logger.error("GET " + url + " result set info: " + rs.toString());
                throw new GraphException("GET " + url + " API call returned code: " + rs.getStatusCode() + " message: " + rs.getStatusMessage());
            }
        }
        catch(GraphException gex) {
            throw gex;
        }
        catch(Exception ex) {
            logger.error("Error fetching vertex with id " + id + ": ", ex);
            throw new GraphException("Error fetching vertex with id " + id + ": " + ex.getMessage());  
        } 
    }

    /**
     * Adds a vertex to the current graph.
     * @param vertex the vertex to be added
     * @return Vertex the vertex object, as returned by IBM Graph, or null
     * @throws GraphException if an error occurred
     * @throws IllegalArgumentException vertex is null      
     */
    public Vertex addVertex(Vertex vertex) throws GraphException, IllegalArgumentException {
        if(vertex == null)
            throw new IllegalArgumentException("vertex parameter is missing");
        try {
            String url = this.apiURL + "/vertices";
            ResultSet rs = new ResultSet(this.doHttpPost(vertex, url));
            // if the vertex was successfully created it can be accessed as the first result in the result set
            if(rs.hasResults()) {
                return rs.getResultAsVertex(0);
            }
            else {
                // Notify caller that we cannnot determine why the edge information was not returned; manual troubleshooting is required
                logger.debug("POST " + url + " result set info: " + rs.toString());
                throw new GraphException("POST " + url + " API call returned code: " + rs.getStatusCode() + " message: " + rs.getStatusMessage());
            }            
        }
        catch(GraphException gex) {
            throw gex;
        }
        catch(Exception ex) {
            throw new GraphException("Error adding vertex: " + ex.getMessage(), ex);              
        }
    }

    /**
     * Updates existing vertex &lt;vertex&gt; by deleting previous properties and replacing them with properties specified as key-value pairs. Vertex labels and IDs are immutable.
     * @param vertex the vertex to be updated
     * @return Vertex the vertex object, as returned by IBM Graph, or null
     * @throws GraphException if an error occurred
     * @throws IllegalArgumentException vertex is null or does not contain an id  
     */
    public Vertex updateVertex(Vertex vertex) throws GraphException, IllegalArgumentException {
       if(vertex == null)
            throw new IllegalArgumentException("vertex parameter is missing");            
       if(vertex.getId() == null)
            throw new IllegalArgumentException("vertex parameter does not contain the id property");
        try {
            String url = this.apiURL + "/vertices/" + vertex.getId();
            // create the payload         
            JSONObject payload = new JSONObject();
            payload.put("properties", vertex.getProperties());
            ResultSet rs = new ResultSet(this.doHttpPut(payload, url));
            // if the vertex was successfully updated it can be accessed as the first result in the result set
            if(rs.hasResults()) {
                return rs.getResultAsVertex(0);
            }
            else {
                // Notify caller that we cannnot determine why the vertex information was not returned; manual troubleshooting is required
                logger.debug("PUT " + url + " result set info: " + rs.toString());
                throw new GraphException("PUT " + url + " API call returned code: " + rs.getStatusCode() + " message: " + rs.getStatusMessage());
            }    
        }
        catch(GraphException gex) {
            throw gex;
        }
        catch(Exception ex) {
            logger.error("Error updating vertex: ", ex);
            throw new GraphException("Error updating vertex: " + ex.getMessage(), ex);              
        }
    }

    /**
     * Removes the vertex identified by id from the current graph.
     * @param id id of the vertex to be removed
     * @return true if the vertex with the specified id was removed
     * @throws GraphException if an error occurred
     * @throws IllegalArgumentException if id is null
     */
    public boolean deleteVertex(Object id) throws GraphException, IllegalArgumentException {
        if(id == null)
            throw new IllegalArgumentException("id parameter is missing");
        try {
            String url = this.apiURL + "/vertices/" + id;
            ResultSet rs = new ResultSet(this.doHttpDelete(url));
            if(rs.hasResults()) {
                return rs.getResultAsBoolean(0).booleanValue();
            }
            return false;
        }
        catch(Exception ex) {
            logger.debug("Error deleting vertex with id " + id + ": ", ex);
            throw new GraphException("Error deleting vertex with id " + id + ": " + ex.getMessage());          
        }                
    }

    /*
     * ----------------------------------------------------------------
     * Edge methods:
     *  - getEdge
     *  - addEdge
     *  - updateEdge
     *  - deleteEdge
     * ----------------------------------------------------------------     
     */

    /**
     * Gets the edge identified by id from the current graph.
     * @param id edge id
     * @return com.ibm.graph.client.Edge or null if no edge with the specified id exists
     * @throws GraphException if an error occurred
     * @throws IllegalArgumentException id is null 
     */
    public Edge getEdge(Object id) throws GraphException, IllegalArgumentException {
        if(id == null)
            throw new IllegalArgumentException("id parameter is missing");
        try {
            String url = String.format("%s/edges/%s",this.apiURL,id);
            ResultSet rs = new ResultSet(this.doHttpGet(url));
            if(rs.hasResults()) {
                return rs.getResultAsEdge(0);
            }
            else {
                // check status to determine whether no result was returned because the edge was not found
                if("NotFoundError".equalsIgnoreCase(rs.getStatusCode()))
                    return null;

                // Notify caller that we cannnot determine why the edge could not be retrieved; manual troubleshooting is required
                logger.error("GET " + url + " result set info: " + rs.toString());
                throw new GraphException("GET " + url + " API call returned code: " + rs.getStatusCode() + " message: " + rs.getStatusMessage());
            }
        }
        catch(GraphException gex) {
            throw gex;
        }
        catch(Exception ex) {
            logger.error("Error fetching edge with id " + id + ": ", ex);
            throw new GraphException("Error fetching edge with id " + id + ": " + ex.getMessage());  
        } 
    }

    /**
     * Adds an edge to the current graph.
     * @param edge the edge to be added
     * @return Edge the edge object, as returned by IBM Graph
     * @throws GraphException if an error occurred
     * @throws IllegalArgumentException edge is null     
     */
    public Edge addEdge(Edge edge) throws GraphException, IllegalArgumentException {
        if(edge == null)
            throw new IllegalArgumentException("edge parameter is missing");
        try {
            String url = this.apiURL + "/edges";
            ResultSet rs = new ResultSet(this.doHttpPost(edge, url));
            // if the edge was successfully created it can be accessed as the first result in the result set
            if(rs.hasResults()) {
                return rs.getResultAsEdge(0);
            }
            else {
                // Notify caller that we cannnot determine why the edge information was not returned; manual troubleshooting is required
                logger.debug("POST " + url + " result set info: " + rs.toString());
                throw new GraphException("POST " + url + " API call returned code: " + rs.getStatusCode() + " message: " + rs.getStatusMessage());
            }            
        }
        catch(GraphException gex) {
            throw gex;
        }
        catch(Exception ex) {
            logger.error("Error adding edge: ", ex);
            throw new GraphException("Error adding edge: " + ex.getMessage(), ex);              
        }        
    }

    /**
     * Updates the properties of an existing edge in the current graph. The incident vertices and edge label cannot be changed.
     * @param edge the edge to be updated
     * @return Edge the edgex object, as returned by IBM Graph, or null
     * @throws GraphException if an error occurred
     * @throws IllegalArgumentException edge is null     
     */
    public Edge updateEdge(Edge edge) throws GraphException, IllegalArgumentException {
       if(edge == null)
            throw new IllegalArgumentException("edge parameter is missing");
       if(edge.getId() == null)
            throw new IllegalArgumentException("edge parameter does not contain the id property");
        try {
            String url = this.apiURL + "/edges/" + edge.getId();
            // create the payload         
            JSONObject payload = new JSONObject();
            payload.put("properties", edge.getProperties());
            ResultSet rs = new ResultSet(this.doHttpPut(payload, url));
            // if the edge was successfully updated it can be accessed as the first result in the result set
            if(rs.hasResults()) {
                return rs.getResultAsEdge(0);
            }
            else {
                // Notify caller that we cannnot determine why the edge information was not returned; manual troubleshooting is required
                logger.debug("PUT " + url + " result set info: " + rs.toString());
                throw new GraphException("PUT " + url + " API call returned code: " + rs.getStatusCode() + " message: " + rs.getStatusMessage());
            }    
        }
        catch(GraphException gex) {
            throw gex;
        }
        catch(Exception ex) {
            logger.error("Error updating edge: ", ex);
            throw new GraphException("Error updating edge: " + ex.getMessage(), ex);              
        }
    }

    /**
     * Removes an edge from the current graph.
     * @param id id of the edge to be removed
     * @return true if the edge with the specified id was removed
     * @throws GraphException if an error occurred
     * @throws IllegalArgumentException id is null
     */
    public boolean deleteEdge(Object id) throws GraphException, IllegalArgumentException {
        if(id == null)
            throw new IllegalArgumentException("id parameter is missing"); 
        try {
            String url = this.apiURL + "/edges/" + id;
            ResultSet rs = new ResultSet(this.doHttpDelete(url));
            if(rs.hasResults()) {
                return rs.getResultAsBoolean(0).booleanValue();
            }
            return false;
        }
        catch(Exception ex) {
            logger.debug("Error deleting edge with id " + id + ": ", ex);
            throw new GraphException("Error deleting edge with id " + id + ": " + ex.getMessage());          
        }                
    }

    /*
     * ----------------------------------------------------------------
     * Bulk load methods
     *  - loadGraphSON
     *  - loadGraphSONfromFile
     *  TODO
     *  - loadGraphML
     *  - loadGraphMLfromFile
     * ----------------------------------------------------------------     
     */

    /**
     * Loads graphSON string into the graph
     * @param graphson data to be loaded
     * @return boolean true if the data was loaded
     * @throws GraphClientException if an error occurred
     * @throws GraphException if an error occurred
     * @throws IllegalArgumentException if graphson is an empty String or exceeds 10MB
     */
    public boolean loadGraphSON(String graphson) throws GraphException, GraphClientException, IllegalArgumentException {
        if((graphson == null) || (graphson.trim().length() == 0)){
            throw new IllegalArgumentException("graphson parameter is missing or empty.");
        }
        if(graphson.length() > 10485760) { // (10 * 1024 * 1024)
            throw new IllegalArgumentException("graphson parameter value exceeds maximum length (10MB).");
        }
        if (this.gdsTokenAuth == null) {
            this.initSession();
        }
        try {
            String url = this.apiURL + "/bulkload/graphson/";
            HttpPost httpPost = new HttpPost(url);
            httpPost.setHeader("Authorization", this.gdsTokenAuth);
            httpPost.setHeader("Accept", "application/json");
            MultipartEntityBuilder meb = MultipartEntityBuilder.create();
            meb.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);
            meb.addPart("graphson", new StringBody(graphson,ContentType.MULTIPART_FORM_DATA));
            httpPost.setEntity(meb.build());

            // TODO debug request parameters
            // if (logger.isDebugEnabled()) {
            //    logger.debug(String.format("Making HTTP POST request to %s; payload=%s", httpPost.toString()));
            //}

            ResultSet rs = new ResultSet(doHttpRequest(httpPost));
            if("200".equals(rs.getStatusCode()) && (rs.hasResults()) && ("true".equals(rs.getResultAsString(0)))) {
                return true;
            }
            return false;
        }
        catch(Exception ex) {
            logger.error("Error loading graphSON into graph: ", ex);
            throw new GraphException("Error loading graphSON into graph: " + ex.getMessage());          
        }  
    } 

    /**
     * Loads graphSON from filename into the graph
     * @param filename file containing graphSON
     * @return boolean true if the data was loaded
     * @throws GraphClientException if an error occurred
     * @throws GraphException if an error occurred
     * @throws IllegalArgumentException if filename does not identify a valid file (exists, is readable and less than 10MB in size)
     */
    public boolean loadGraphSONfromFile(String filename) throws GraphException, GraphClientException, IllegalArgumentException {
        if((filename == null) || (filename.trim().length() == 0)){
            throw new IllegalArgumentException("filename parameter is missing or empty.");
        }
        File graphsonFile = new File(filename);
        if(! graphsonFile.canRead()) {
            throw new IllegalArgumentException("File " + filename + " was not found or cannot be read.");
        }
        if(graphsonFile.length() > 10485760) { // (10 * 1024 * 1024)
            throw new IllegalArgumentException("File " + filename + " is larger than 10MB and can therefore not be processed.");
        }

        if (this.gdsTokenAuth == null) {
            this.initSession();
        }
        try {
            String url = this.apiURL + "/bulkload/graphson/";

            HttpPost httpPost = new HttpPost(url);
            httpPost.setHeader("Authorization", this.gdsTokenAuth);
            httpPost.setHeader("Accept", "application/json");
            FileBody fb = new FileBody(graphsonFile);
            MultipartEntityBuilder meb = MultipartEntityBuilder.create();
            meb.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);
            meb.addPart("graphson", fb);
            httpPost.setEntity(meb.build());

            // TODO debug request parameters
            // if (logger.isDebugEnabled()) {
            //    logger.debug(String.format("Making HTTP POST request to %s; payload=%s", httpPost.toString()));
            //}

            ResultSet rs = new ResultSet(doHttpRequest(httpPost));
            if("200".equals(rs.getStatusCode()) && (rs.hasResults()) && ("true".equals(rs.getResultAsString(0)))) {
                return true;
            }
            return false;
        }
        catch(Exception ex) {
            logger.error("Error loading graphSON into graph: ", ex);
            throw new GraphException("Error loading graphSON into graph: " + ex.getMessage());          
        }  
    } 

    /*
     * ----------------------------------------------------------------
     * Gremlin methods:
     *  - executeGremlin
     * ----------------------------------------------------------------     
     */
    
    /**
     * Runs the specified Gremlin 
     * @param gremlin the traversal to be performed
     * @return ResultSet the result of the graph traversal
     * @throws GraphException if an error occurred
     * @throws IllegalArgumentException gremlin is null or an empty string
     */
    public ResultSet executeGremlin(String gremlin) throws GraphException, IllegalArgumentException {
        return executeGremlin(gremlin, null);
    }

    /**
     * Runs the specified Gremlin using the optionally provided bindings. 
     * @param gremlin the traversal to be performed
     * @param bindings optional gremlin bindings. 
     * @return ResultSet the result of the graph traversal
     * @throws GraphException if an error occurred
     * @throws IllegalArgumentException gremlin is null or an empty string or bindings is an empty string
     */
    public ResultSet executeGremlin(String gremlin, HashMap<String, Object> bindings) throws GraphException, IllegalArgumentException {
        if((gremlin == null) || (gremlin.trim().length() == 0)) {
            throw new IllegalArgumentException("gremlin parameter is null or empty.");
        }
        
        if (logger.isDebugEnabled()) {
            logger.debug("Executing gremlin \"" + gremlin + "\" bindings: " + bindings);
        }
        try {
            String url = this.apiURL + "/gremlin";
            JSONObject postData = new JSONObject();
            postData.put("gremlin", String.format("def g = graph.traversal(); %s",gremlin));
            if((bindings != null) && (! bindings.isEmpty()))
                postData.put("bindings", bindings);
            return new ResultSet(this.doHttpPost(postData, url));
        }
        catch(Exception ex) {
            logger.error("Error executing gremlin \"" + gremlin + "\" bindings: " + bindings, ex);
            throw new GraphException("Error processing gremlin " + gremlin + ": " + ex.getMessage());               
        }
    }    


    /*
     * ----------------------------------------------------------------
     * HTTP helper methods
     *  - doHttpGet
     *  - doHttpPost
     *  - doHttpPut
     *  - doHttpDelete
     *  - doHttpRequest
     * ----------------------------------------------------------------     
     */

    private GraphResponse doHttpGet(String url) throws GraphClientException {
        if (this.gdsTokenAuth == null) {
            this.initSession();
        }
        HttpGet httpGet = new HttpGet(url);
        httpGet.setHeader("Authorization", this.gdsTokenAuth);
        httpGet.setHeader("Accept", "application/json");
        logger.debug(String.format("Making HTTP GET request to %s",url));
        return doHttpRequest(httpGet);
    }

    private GraphResponse doHttpPost(JSONObject json, String url) throws GraphClientException {
        if (this.gdsTokenAuth == null) {
            this.initSession();
        }
        String payload = (json == null ? "" : json.toString());
        HttpPost httpPost = new HttpPost(url);
        httpPost.setHeader("Authorization", this.gdsTokenAuth);
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("Accept", "application/json");
        httpPost.setEntity(new StringEntity(payload, ContentType.APPLICATION_JSON));
        logger.debug(String.format("Making HTTP POST request to %s; payload=%s",url,payload));
        return doHttpRequest(httpPost);
    }

    private GraphResponse doHttpPut(JSONObject json, String url) throws GraphClientException {
        if (this.gdsTokenAuth == null) {
            this.initSession();
        }
        String payload = json.toString();
        HttpPut httpPut = new HttpPut(url);
        httpPut.setHeader("Authorization", this.gdsTokenAuth);
        httpPut.setHeader("Content-Type", "application/json");
        httpPut.setHeader("Accept", "application/json");
        httpPut.setEntity(new StringEntity(payload, ContentType.APPLICATION_JSON));
        logger.debug(String.format("Making HTTP PUT request to %s; payload=%s",url,payload));
        return doHttpRequest(httpPut);
    }

    private GraphResponse doHttpDelete(String url) throws GraphClientException {
        if (this.gdsTokenAuth == null) {
            this.initSession();
        }
        HttpDelete httpDelete = new HttpDelete(url);
        httpDelete.setHeader("Authorization", this.gdsTokenAuth);
        httpDelete.setHeader("Accept", "application/json");
        logger.debug(String.format("Making HTTP DELETE request to %s",url));
        return doHttpRequest(httpDelete);
    }

    /**
     * Executes an HTTP request.
     * @param request the request to be executed
     * @returns GraphResponse for this request
     * @throws GraphClientException if a fatal  error was encountered 
     **/
    private GraphResponse doHttpRequest(HttpUriRequest request) throws GraphClientException {
        CloseableHttpClient httpclient = HttpClients.createDefault();
        CloseableHttpResponse httpResponse = null;
        try {                
            logger.debug(String.format("Sending HTTP request %s", request.toString()));
            httpResponse = httpclient.execute(request);
            HttpEntity httpEntity = httpResponse.getEntity();
            String content = EntityUtils.toString(httpEntity);
            EntityUtils.consume(httpEntity);

            // display <status code> <status message> <body>
            // display 200 OK Hello World 
            logger.debug(String.format("Response received from %s = %s %s %s",                    
                                       request.getURI(), 
                                       httpResponse.getStatusLine().getStatusCode(), 
                                       httpResponse.getStatusLine().getReasonPhrase(), 
                                       content));  

            // assemble response information
            GraphResponse response = new GraphResponse(new HTTPResponseInfo(httpResponse.getStatusLine().getStatusCode(),
                                                                            httpResponse.getStatusLine().getReasonPhrase()),
                                                       content);
            return response;
        }
        catch(Exception ex) {
            logger.error("Error processing HTTP request ", ex);            
            throw new GraphClientException("Error processing HTTP request: " + ex.getMessage());
        }
        finally {
            if(httpResponse != null) {
                try {
                    httpResponse.close();
                }
                catch(IOException ioex) {
                    // ignore
                }
            }
                
        }
    }
}