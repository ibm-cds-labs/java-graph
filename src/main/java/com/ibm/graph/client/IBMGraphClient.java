package com.ibm.graph.client;

import com.ibm.graph.client.schema.Schema;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.*;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.wink.json4j.JSONArray;
import org.apache.wink.json4j.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
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
    private String basicAuthHeader;
    private String gdsTokenAuth;

    private static Logger logger =  LoggerFactory.getLogger(IBMGraphClient.class);

    public IBMGraphClient() throws Exception {
        Map envs = System.getenv();
        if (envs.get("VCAP_SERVICES") != null) {
            String graphServiceName = "IBM Graph";
            JSONObject vcapSvcs = new JSONObject(envs.get("VCAP_SERVICES").toString());
            if (!vcapSvcs.isNull(graphServiceName)) {
                JSONObject creds = vcapSvcs.getJSONArray(graphServiceName).getJSONObject(0).getJSONObject("credentials");
                this.apiURL = creds.getString("apiURL");
                this.username = creds.getString("username");
                this.password = creds.getString("password");
                this.init();
            }
        }
    }

    public IBMGraphClient(String apiURL, String username, String password) {
        this.apiURL = apiURL;
        this.username = username;
        this.password = password;
        this.init();
    }

    private void init() {
        this.basicAuthHeader = "Basic " + Base64.getEncoder().encodeToString((this.username + ":" + this.password).getBytes());
        this.baseURL = this.apiURL.substring(0, this.apiURL.lastIndexOf('/'));
    }

    private void initSession() throws Exception {
        if (this.baseURL == null) {
            throw new RuntimeException("Invalid configuration. Please specify a valid apiURL, username, and password.");
        }
        HttpGet httpGet = new HttpGet(this.baseURL + "/_session");
        httpGet.setHeader("Authorization", this.basicAuthHeader);
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
        finally {
            try {
                httpResponse.close();
            }
            catch(IOException ioe) {}
        }
    }

    public Schema getSchema() throws Exception {
        String url = this.apiURL + "/schema";
        JSONObject jsonContent = this.doHttpGet(url);
        JSONArray data = jsonContent.getJSONObject("result").getJSONArray("data");
        if (data.length() > 0) {
            return Schema.fromJSONObject(data.getJSONObject(0));
        }
        return null;
    }

    public Schema saveSchema(Schema schema) throws Exception {
        String url = this.apiURL + "/schema";
        JSONObject jsonContent = this.doHttpPost(schema, url);
        JSONArray data = jsonContent.getJSONObject("result").getJSONArray("data");
        if (data.length() > 0) {
            return Schema.fromJSONObject(data.getJSONObject(0));
        }
        return null;
    }

    public boolean deleteIndex(String indexName) throws Exception {
        String url = this.apiURL + "/index/" + indexName;
        JSONObject jsonContent = this.doHttpDelete(url);
        return jsonContent.getJSONObject("result").getJSONArray("data").getBoolean(0);
    }

    public Vertex addVertex(Vertex vertex) throws Exception {
        String url = this.apiURL + "/vertices";
        JSONObject jsonContent = this.doHttpPost(vertex, url);
        JSONArray data = jsonContent.getJSONObject("result").getJSONArray("data");
        if (data.length() > 0) {
            return Vertex.fromJSONObject(data.getJSONObject(0));
        }
        return null;
    }

    public Vertex updatedVertex(Vertex vertex) throws Exception {
        String url = this.apiURL + "/vertices/" + vertex.getId();
        JSONObject jsonContent = this.doHttpPut(vertex, url);
        JSONArray data = jsonContent.getJSONObject("result").getJSONArray("data");
        if (data.length() > 0) {
            return Vertex.fromJSONObject(data.getJSONObject(0));
        }
        return null;
    }

    public Edge addEdge(Edge edge) throws Exception {
        String url = this.apiURL + "/edges";
        JSONObject jsonContent = this.doHttpPost(edge, url);
        JSONArray data = jsonContent.getJSONObject("result").getJSONArray("data");
        if (data.length() > 0) {
            return Edge.fromJSONObject(data.getJSONObject(0));
        }
        return null;
    }

    public Edge updateEdge(Edge edge) throws Exception {
        String url = this.apiURL + "/edges/" + edge.getId();
        JSONObject jsonContent = this.doHttpPut(edge, url);
        JSONArray data = jsonContent.getJSONObject("result").getJSONArray("data");
        if (data.length() > 0) {
            return Edge.fromJSONObject(data.getJSONObject(0));
        }
        return null;
    }

    public boolean deleteVertex(Object id) throws Exception {
        String url = this.apiURL + "/vertices/" + id;
        JSONObject jsonContent = this.doHttpDelete(url);
        return jsonContent.getJSONObject("result").getJSONArray("data").getBoolean(0);
    }

    public Element[] runGremlinQuery(String query) throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("Running Gremlin Query: " + query);
        }
        String url = this.apiURL + "/gremlin";
        JSONObject postData = new JSONObject();
        postData.put("gremlin", String.format("def g = graph.traversal(); %s",query));
        JSONObject jsonContent = this.doHttpPost(postData, url);
        JSONArray data = jsonContent.getJSONObject("result").getJSONArray("data");
        List<Element> elements = new ArrayList<Element>();
        if (data.length() > 0) {
            for (int i = 0 ; i < data.length(); i++) {
                elements.add(Element.fromJSONObject(data.getJSONObject(i)));
            }
        }
        return elements.toArray(new Element[0]);
    }
    
    // HTTP Helper Methods

    private JSONObject doHttpGet(String url) throws Exception {
        if (this.gdsTokenAuth == null) {
            this.initSession();
        }
        HttpGet httpGet = new HttpGet(url);
        httpGet.setHeader("Authorization", this.gdsTokenAuth);
        httpGet.setHeader("Accept", "application/json");
        if (logger.isDebugEnabled()) {
            logger.debug(String.format("Making HTTP GET request to %s",url));
        }
        return doHttpRequest(httpGet);
    }

    private JSONObject doHttpPost(JSONObject json, String url) throws Exception {
        if (this.gdsTokenAuth == null) {
            this.initSession();
        }
        String payload = json.toString();
        HttpPost httpPost = new HttpPost(url);
        httpPost.setHeader("Authorization", this.gdsTokenAuth);
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("Accept", "application/json");
        httpPost.setEntity(new StringEntity(payload, ContentType.APPLICATION_JSON));
        if (logger.isDebugEnabled()) {
            logger.debug(String.format("Making HTTP POST request to %s; payload=%s",url,payload));
        }
        return doHttpRequest(httpPost);
    }

    private JSONObject doHttpPut(JSONObject json, String url) throws Exception {
        if (this.gdsTokenAuth == null) {
            this.initSession();
        }
        String payload = json.toString();
        HttpPut httpPut = new HttpPut(url);
        httpPut.setHeader("Authorization", this.gdsTokenAuth);
        httpPut.setHeader("Content-Type", "application/json");
        httpPut.setHeader("Accept", "application/json");
        httpPut.setEntity(new StringEntity(payload, ContentType.APPLICATION_JSON));
        if (logger.isDebugEnabled()) {
            logger.debug(String.format("Making HTTP PUT request to %s; payload=%s",url,payload));
        }
        return doHttpRequest(httpPut);
    }

    private JSONObject doHttpDelete(String url) throws Exception {
        if (this.gdsTokenAuth == null) {
            this.initSession();
        }
        HttpDelete httpDelete = new HttpDelete(url);
        httpDelete.setHeader("Authorization", this.gdsTokenAuth);
        httpDelete.setHeader("Accept", "application/json");
        if (logger.isDebugEnabled()) {
            logger.debug(String.format("Making HTTP DELETE request to %s",url));
        }
        return doHttpRequest(httpDelete);
    }

    private JSONObject doHttpRequest(HttpUriRequest request) throws Exception {
        CloseableHttpClient httpclient = HttpClients.createDefault();
        CloseableHttpResponse httpResponse = null;
        try {
            httpResponse = httpclient.execute(request);
            HttpEntity httpEntity = httpResponse.getEntity();
            String content = EntityUtils.toString(httpEntity);
            EntityUtils.consume(httpEntity);
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("Response received from %s = %s",request.getURI(),content));
            }
            return new JSONObject(content);
        }
        finally {
            httpResponse.close();
        }
    }
}