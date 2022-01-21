/**
 *  ElasticsearchClient
 *  Copyright 18.02.2016 by Michael Peter Christen, @orbiterlab
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General private
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General private License for more details.
 *
 *  You should have received a copy of the GNU Lesser General private License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package net.yacy.grid.io.index;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.lucene.search.Explanation;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.cluster.stats.ClusterStatsAction;
import org.elasticsearch.action.admin.cluster.stats.ClusterStatsNodes;
import org.elasticsearch.action.admin.cluster.stats.ClusterStatsRequest;
import org.elasticsearch.action.admin.cluster.stats.ClusterStatsRequestBuilder;
import org.elasticsearch.action.admin.cluster.stats.ClusterStatsResponse;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsRequest;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.get.MultiGetItemResponse;
import org.elasticsearch.action.get.MultiGetRequestBuilder;
import org.elasticsearch.action.get.MultiGetResponse;
import org.elasticsearch.action.search.SearchPhaseExecutionException;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.NoNodeAvailableException;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.bucket.terms.Terms.Bucket;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.elasticsearch.transport.client.PreBuiltTransportClient;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * To get data out of the elasticsearch index which is written with this client, try:
 * http://localhost:9200/web/_search?q=*:*
 * http://localhost:9200/crawler/_search?q=*:*
 *
 */
public class ElasticsearchClient implements FulltextIndex {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchClient.class);

    private static final TimeValue scrollKeepAlive = TimeValue.timeValueSeconds(60);
    private static long throttling_time_threshold = 2000L; // update time high limit
    private static long throttling_ops_threshold = 1000L; // messages per second low limit
    private static double throttling_factor = 1.0d; // factor applied on update duration if both thresholds are passed

    private final String[] addresses;
    private final String clusterName;
    private Client elasticsearchClient;

    /**
     * create a elasticsearch transport client (remote elasticsearch)
     * @param addresses an array of host:port addresses
     * @param clusterName
     */
    public ElasticsearchClient(final String[] addresses, final String clusterName) {
        log.info("ElasticsearchClient initiated client, " + addresses.length + " address: " + addresses[0] + ", clusterName: " + clusterName);
        this.addresses = addresses;
        this.clusterName = clusterName;
        connect();
    }

    private void connect() {
        // create default settings and add cluster name
        final Settings.Builder settings = Settings.builder()
                .put("cluster.routing.allocation.enable", "all")
                .put("cluster.routing.allocation.allow_rebalance", "always");
        if (this.clusterName != null && this.clusterName.length() > 0) settings.put("cluster.name", this.clusterName);

        // create a client
        // newClient = new RestHighLevelClient(RestClient.builder(new HttpHost(host, port, "http"))); // future initialization method
        System.setProperty("es.set.netty.runtime.available.processors", "false"); // patch which prevents io.netty.util.NettyRuntime$AvailableProcessorsHolder.setAvailableProcessors from failing
        TransportClient newClient = null;
        while (true) try {
            newClient = new PreBuiltTransportClient(settings.build());
            break;
        } catch (final Exception e) {
            log.warn("failed to create an elastic client, retrying...", e);
            try { Thread.sleep(10000); } catch (final InterruptedException e1) {}
        }

        for (final String address: this.addresses) {
            final String a = address.trim();
            final int p = a.indexOf(':');
            if (p >= 0) try {
                final InetAddress i = InetAddress.getByName(a.substring(0, p));
                final int port = Integer.parseInt(a.substring(p + 1));
                //tc.addTransportAddress(new InetSocketTransportAddress(i, port));
                final TransportAddress ta = new TransportAddress(i, port);
                log.info("Elasticsearch: added TransportAddress " + ta.toString());
                newClient.addTransportAddress(ta);
            } catch (final UnknownHostException e) {
                log.warn("", e);
            }
        }

        // replace old client with new client
        final Client oldClient = this.elasticsearchClient;
        this.elasticsearchClient = newClient; // just switch out without closeing the old one first
        // because closing may cause blocking, we close this concurrently
        if (oldClient != null) new Thread() {
            @Override
            public void run() {
                this.setName("temporary client close job " + ElasticsearchClient.this.clusterName);
                try {
                    oldClient.close();
                } catch (NoNodeAvailableException | IllegalStateException | ClusterBlockException | SearchPhaseExecutionException e) {}
            }
        }.start();
        
        // check if client is ready
        log.info("Elasticsearch: node is " + (clusterReady() ? "ready" : "not ready"));
    }

    @SuppressWarnings("unused")
    private ClusterStatsNodes getClusterStatsNodes() {
        final ClusterStatsRequest clusterStatsRequest =
            new ClusterStatsRequestBuilder(this.elasticsearchClient.admin().cluster(), ClusterStatsAction.INSTANCE).request();
        final ClusterStatsResponse clusterStatsResponse =
            this.elasticsearchClient.admin().cluster().clusterStats(clusterStatsRequest).actionGet();
        final ClusterStatsNodes clusterStatsNodes = clusterStatsResponse.getNodesStats();
        return clusterStatsNodes;
    }

    private boolean clusterReadyCache = false;

    @SuppressWarnings("unused")
    public boolean clusterReady() {
        if (this.clusterReadyCache) return true;
        final ClusterHealthResponse chr = this.elasticsearchClient.admin().cluster().prepareHealth().get();
        this.clusterReadyCache = chr.getStatus() != ClusterHealthStatus.RED;
        return this.clusterReadyCache;
    }

    @SuppressWarnings("unused")
    private boolean wait_ready(long maxtimemillis, ClusterHealthStatus status) {
        // wait for yellow status
        final long start = System.currentTimeMillis();
        boolean is_ready;
        do {
            // wait for yellow status
            final ClusterHealthResponse health = this.elasticsearchClient.admin().cluster().prepareHealth().setWaitForStatus(status).execute().actionGet();
            is_ready = !health.isTimedOut();
            if (!is_ready && System.currentTimeMillis() - start > maxtimemillis) return false;
        } while (!is_ready);
        return is_ready;
    }

    /**
     * A refresh request making all operations performed since the last refresh available for search. The (near) real-time
     * capabilities depends on the index engine used. For example, the internal one requires refresh to be called, but by
     * default a refresh is scheduled periodically.
     * If previous indexing steps had been done, it is required to call this method to get most recent documents into the search results.
     */
    @Override
    public void refresh(String indexName) {
        new RefreshRequest(indexName);
    }

    public void settings(String indexName) {
        final UpdateSettingsRequest request = new UpdateSettingsRequest(indexName);
        final String settingKey = "index.mapping.total_fields.limit";
        final int settingValue = 10000;
        final Settings.Builder settingsBuilder =
                Settings.builder()
                .put(settingKey, settingValue);
        request.settings(settingsBuilder);
        final CreateIndexRequest updateSettingsResponse =
                this.elasticsearchClient.admin().indices().prepareCreate(indexName).setSettings(settingsBuilder).request();
    }

    /**
     * create a new index. This method must be called to ensure that an elasticsearch index is available and can be used.
     * @param indexName
     * @param shards
     * @param replicas
     * @throws NoNodeAvailableException | IllegalStateException in case that no elasticsearch server can be contacted.
     */
    @Override
    public void createIndexIfNotExists(String indexName, final int shards, final int replicas) {
        // create an index if not existent
        if (!this.elasticsearchClient.admin().indices().prepareExists(indexName).execute().actionGet().isExists()) {
            final Settings.Builder settings = Settings.builder()
                    .put("number_of_shards", shards)
                    .put("number_of_replicas", replicas);
            this.elasticsearchClient.admin().indices().prepareCreate(indexName)
                .setSettings(settings)
                .execute().actionGet();
        } else {
            //LOGGER.debug("Index with name {} already exists", indexName);
        }
    }

    @Override
    public void setMapping(String indexName, String mapping) {
        try {
            this.elasticsearchClient.admin().indices().preparePutMapping(indexName)
                .setSource(mapping, XContentType.JSON)
                .setType("_default_").execute().actionGet();
        } catch (NoNodeAvailableException | IllegalStateException | ClusterBlockException | SearchPhaseExecutionException e) {
            log.warn("", e);
        };
    }

    /**
     * Close the connection to the remote elasticsearch client. This should only be called when the application is
     * terminated.
     * Please avoid to open and close the ElasticsearchClient for the same cluster and index more than once.
     * To avoid that this method is called more than once, the elasticsearch_client object is set to null
     * as soon this was called the first time. This is needed because the finalize method calls this
     * method as well.
     */
    @Override
    public void close() {
        if (this.elasticsearchClient != null) {
            this.elasticsearchClient.close();
            this.elasticsearchClient = null;
        }
    }

    /**
     * A finalize method is added to ensure that close() is always called.
     */
    @Override
    public void finalize() {
        this.close(); // will not cause harm if this is the second call to close()
    }

    /**
     * Retrieve a statistic object from the connected elasticsearch cluster
     *
     * @return cluster stats from connected cluster
     */
    @SuppressWarnings("unused")
    private ClusterStatsNodes getStats() {
        final ClusterStatsRequest clusterStatsRequest =
            new ClusterStatsRequestBuilder(this.elasticsearchClient.admin().cluster(), ClusterStatsAction.INSTANCE).request();
        final ClusterStatsResponse clusterStatsResponse =
            this.elasticsearchClient.admin().cluster().clusterStats(clusterStatsRequest).actionGet();
        final ClusterStatsNodes clusterStatsNodes = clusterStatsResponse.getNodesStats();
        return clusterStatsNodes;
    }

    /**
     * Get the number of documents in the search index
     *
     * @return the count of all documents in the index
     */
    private long count(String indexName) {
        final QueryBuilder q = QueryBuilders.constantScoreQuery(QueryBuilders.matchAllQuery());
        while (true) try {
            return countInternal(q, indexName);
        } catch (NoNodeAvailableException | IllegalStateException | ClusterBlockException | SearchPhaseExecutionException e) {
            log.info("ElasticsearchClient count failed with " + e.getMessage() + ", retrying to connect node...");
            try {Thread.sleep(1000);} catch (final InterruptedException ee) {}
            connect();
        }
    }

    /**
     * Get the number of documents in the search index for a given search query
     *
     * @param q
     *            the query
     * @return the count of all documents in the index which matches with the query
     */
    @Override
    public long count(final String indexName, final YaCyQuery yq) {
        final QueryBuilder q = yq.getQueryBuilder();
        while (true) try {
            return countInternal(q, indexName);
        } catch (NoNodeAvailableException | IllegalStateException | ClusterBlockException | SearchPhaseExecutionException e) {
            log.info("ElasticsearchClient count failed with " + e.getMessage() + ", retrying to connect node...");
            try {Thread.sleep(1000);} catch (final InterruptedException ee) {}
            connect();
        }
    }

    private long countInternal(final QueryBuilder q, final String indexName) {
        final SearchResponse response = this.elasticsearchClient.prepareSearch(indexName).setQuery(q).setSize(0).execute().actionGet();
        return response.getHits().getTotalHits();
    }

    /**
     * Get the document for a given id.
     * @param indexName the name of the index
     * @param id the unique identifier of a document
     * @return the document, if it exists or null otherwise;
     */
    @Override
    public boolean exist(String indexName, final String id) {
        while (true) try {
            return existInternal(indexName, id);
        } catch (NoNodeAvailableException | IllegalStateException | ClusterBlockException | SearchPhaseExecutionException e) {
            log.info("ElasticsearchClient exist failed with " + e.getMessage() + ", retrying to connect node...");
            try {Thread.sleep(1000);} catch (final InterruptedException ee) {}
            connect();
        }
    }

    private boolean existInternal(String indexName, final String id) {
        final GetResponse getResponse = this.elasticsearchClient
                .prepareGet(indexName, null, id)
                .setFetchSource(false)
                //.setOperationThreaded(false)
                .execute()
                .actionGet();
        return getResponse.isExists();
    }

    @Override
    public Set<String> existBulk(String indexName, final Collection<String> ids) {
        while (true) try {
            return existBulkInternal(indexName, ids);
        } catch (NoNodeAvailableException | IllegalStateException | ClusterBlockException | SearchPhaseExecutionException e) {
            log.info("ElasticsearchClient existBulk failed with " + e.getMessage() + ", retrying to connect node...");
            try {Thread.sleep(1000);} catch (final InterruptedException ee) {}
            connect();
            continue;
        }
    }

    private Set<String> existBulkInternal(String indexName, final Collection<String> ids) {
        if (ids == null || ids.size() == 0) return new HashSet<>();
        final MultiGetResponse multiGetItemResponses = this.elasticsearchClient.prepareMultiGet()
                .add(indexName, null, ids)
                .get();
        final Set<String> er = new HashSet<>();
        for (final MultiGetItemResponse itemResponse : multiGetItemResponses) {
            final GetResponse response = itemResponse.getResponse();
            if (response.isExists()) {
                er.add(response.getId());
            }
        }
        return er;
    }

    /**
     * Get the type name of a document or null if the document does not exist.
     * This is a replacement of the exist() method which does exactly the same as exist()
     * but is able to return the type name in case that exist is successful.
     * Please read the comment to exist() for details.
     * @param indexName
     *            the name of the index
     * @param id
     *            the unique identifier of a document
     * @return the type name of the document if it exists, null otherwise
     */
    @SuppressWarnings("unused")
    private String getType(String indexName, final String id) {
        final GetResponse getResponse = this.elasticsearchClient.prepareGet(indexName, null, id).execute().actionGet();
        return getResponse.isExists() ? getResponse.getType() : null;
    }

    /**
     * Delete a document for a given id.
     * ATTENTION: deleted documents cannot be re-inserted again if version number
     * checking is used and the new document does not comply to the version number
     * rule. The information which document was deleted persists for one minute and
     * then inserting documents with the same version number as before is possible.
     * To modify this behavior, change the configuration setting index.gc_deletes
     *
     * @param id
     *            the unique identifier of a document
     * @return true if the document existed and was deleted, false otherwise
     */
    @Override
    public boolean delete(String indexName, String typeName, final String id) {
        while (true) try {
            return deleteInternal(indexName, typeName, id);
        } catch (NoNodeAvailableException | IllegalStateException | ClusterBlockException | SearchPhaseExecutionException e) {
            log.info("ElasticsearchClient delete failed with " + e.getMessage() + ", retrying to connect node...");
            try {Thread.sleep(1000);} catch (final InterruptedException ee) {}
            connect();
            continue;
        }
    }

    private boolean deleteInternal(String indexName, String typeName, final String id) {
        final DeleteResponse response = this.elasticsearchClient.prepareDelete(indexName, typeName, id).get();
        return response.getResult() == DocWriteResponse.Result.DELETED;
    }

    /**
     * Delete documents using a query. Check what would be deleted first with a normal search query!
     * Elasticsearch once provided a native prepareDeleteByQuery method, but this was removed
     * in later versions. Instead, there is a plugin which iterates over search results,
     * see https://www.elastic.co/guide/en/elasticsearch/plugins/current/plugins-delete-by-query.html
     * We simulate the same behaviour here without the need of that plugin.
     *
     * @param q
     * @return delete document count
     */
    @Override
    public int deleteByQuery(String indexName, final YaCyQuery yq) {
        final QueryBuilder q = yq.getQueryBuilder();
        while (true) try {
            return deleteByQueryInternal(indexName, q);
        } catch (NoNodeAvailableException | IllegalStateException | ClusterBlockException | SearchPhaseExecutionException e) {
            log.info("ElasticsearchClient deleteByQuery failed with " + e.getMessage() + ", retrying to connect node...");
            try {Thread.sleep(1000);} catch (final InterruptedException ee) {}
            connect();
            continue;
        }
    }

    private int deleteByQueryInternal(String indexName, final QueryBuilder q) {
        final Map<String, String> ids = new TreeMap<>();
        final SearchRequestBuilder request = this.elasticsearchClient.prepareSearch(indexName);
        request
            .setSearchType(SearchType.QUERY_THEN_FETCH)
            .setScroll(scrollKeepAlive)
            .setQuery(q)
            .setSize(100);
        SearchResponse response = request.execute().actionGet();
        while (true) {
            // accumulate the ids here, don't delete them right now to prevent an interference of the delete with the
            // scroll
            for (final SearchHit hit : response.getHits().getHits()) {
                ids.put(hit.getId(), hit.getType());
            }
            // termination
            if (response.getHits().getHits().length == 0) break;
            // scroll
            response = this.elasticsearchClient.prepareSearchScroll(response.getScrollId()).setScroll(scrollKeepAlive).execute().actionGet();
        }
        return deleteBulk(indexName, ids);
    }

    /**
     * Delete a list of documents for a given set of ids
     * ATTENTION: read about the time-out of version number checking in the method above.
     *
     * @param ids
     *            a map from the unique identifier of a document to the document type
     * @return the number of deleted documents
     */
    private int deleteBulk(String indexName, Map<String, String> ids) {
        // bulk-delete the ids
        if (ids == null || ids.size() == 0) return 0;
        final BulkRequestBuilder bulkRequest = this.elasticsearchClient.prepareBulk();
        for (final Map.Entry<String, String> id : ids.entrySet()) {
            bulkRequest.add(new DeleteRequest().id(id.getKey()).index(indexName).type(id.getValue()));
        }
        bulkRequest.execute().actionGet();
        return ids.size();
    }

    /**
     * Read a document from the search index for a given id.
     * This is the cheapest document retrieval from the '_source' field because
     * elasticsearch does not do any json transformation or parsing. We
     * get simply the text from the '_source' field. This might be useful to
     * make a dump from the index content.
     *
     * @param id
     *            the unique identifier of a document
     * @return the document as source text
     */
    @SuppressWarnings("unused")
    private byte[] readSource(String indexName, final String id) {
        final GetResponse response = this.elasticsearchClient.prepareGet(indexName, null, id).execute().actionGet();
        return response.getSourceAsBytes();
    }

    /**
     * Read a json document from the search index for a given id.
     * Elasticsearch reads the '_source' field and parses the content as json.
     *
     * @param id
     *            the unique identifier of a document
     * @return the document as json, matched on a Map<String, Object> object instance
     */
    @Override
    public Map<String, Object> readMap(final String indexName, final String id) {
        while (true) try {
            return readMapInternal(indexName, id);
        } catch (NoNodeAvailableException | IllegalStateException | ClusterBlockException | SearchPhaseExecutionException e) {
            log.info("ElasticsearchClient readMap failed with " + e.getMessage() + ", retrying to connect node...");
            try {Thread.sleep(1000);} catch (final InterruptedException ee) {}
            connect();
            continue;
        }
    }

    private Map<String, Object> readMapInternal(final String indexName, final String id) {
        final GetResponse response = this.elasticsearchClient.prepareGet(indexName, null, id).execute().actionGet();
        final Map<String, Object> map = getMap(response);
        return map;
    }

    @Override
    public Map<String, Map<String, Object>> readMapBulk(final String indexName, final Collection<String> ids) {
        while (true) try {
            return readMapBulkInternal(indexName, ids);
        } catch (NoNodeAvailableException | IllegalStateException | ClusterBlockException | SearchPhaseExecutionException e) {
            log.info("ElasticsearchClient readMapBulk failed with " + e.getMessage() + ", retrying to connect node...");
            try {Thread.sleep(1000);} catch (final InterruptedException ee) {}
            connect();
            continue;
        }
    }

    private Map<String, Map<String, Object>> readMapBulkInternal(final String indexName, final Collection<String> ids) {
        final MultiGetRequestBuilder mgrb = this.elasticsearchClient.prepareMultiGet();
        ids.forEach(id -> mgrb.add(indexName, null, id).execute().actionGet());
        final MultiGetResponse response = mgrb.execute().actionGet();
        final Map<String, Map<String, Object>> bulkresponse = new HashMap<>();
        for (final MultiGetItemResponse r: response.getResponses()) {
            final GetResponse gr = r.getResponse();
            if (gr != null) {
                final Map<String, Object> map = getMap(gr);
                bulkresponse.put(r.getId(), map);
            }
        }
        return bulkresponse;
    }

    protected static Map<String, Object> getMap(GetResponse response) {
        Map<String, Object> map = null;
        if (response.isExists() && (map = response.getSourceAsMap()) != null) {
            if (!map.containsKey("id")) map.put("id", response.getId());
            if (!map.containsKey("type")) map.put("type", response.getType());
        }
        return map;
    }

    /**
     * Write a json document into the search index. The id must be calculated by the calling environment.
     * This id should be unique for the json. The best way to calculate this id is, to use an existing
     * field from the jsonMap which contains a unique identifier for the jsonMap.
     *
     * @param indexName the name of the index
     * @param typeName the type of the index
     * @param id the unique identifier of a document
     * @param jsonMap the json document to be indexed in elasticsearch
     * @return true if the document with given id did not exist before, false if it existed and was overwritten
     */
    @Override
    public boolean writeMap(String indexName, String typeName, String id, final Map<String, Object> jsonMap) {
        while (true) try {
            return writeMapInternal(indexName, typeName, id, jsonMap);
        } catch (NoNodeAvailableException | IllegalStateException | ClusterBlockException | SearchPhaseExecutionException e) {
            log.info("ElasticsearchClient writeMap failed with " + e.getMessage() + ", retrying to connect node...");
            try {Thread.sleep(1000);} catch (final InterruptedException ee) {}
            connect();
            continue;
        }
    }

    // internal method used for a re-try after NoNodeAvailableException | IllegalStateException
    private boolean writeMapInternal(String indexName, String typeName, String id, final Map<String, Object> jsonMap) {
        final long start = System.currentTimeMillis();
        // get the version number out of the json, if any is given
        final Long version = (Long) jsonMap.remove("_version");
        // put this to the index
        final UpdateResponse r = this.elasticsearchClient
            .prepareUpdate(indexName, typeName, id)
            .setDoc(jsonMap)
            .setUpsert(jsonMap)
            //.setVersion(version == null ? 1 : version.longValue())
            //.setVersionType(VersionType.EXTERNAL_GTE)
            .execute()
            .actionGet();
        if (version != null) jsonMap.put("_version", version); // to prevent side effects
        // documentation about the versioning is available at
        // https://www.elastic.co/blog/elasticsearch-versioning-support
        // TODO: error handling
        final boolean created = r != null && r.status() == RestStatus.CREATED; // true means created, false means updated
        final long duration = Math.max(1, System.currentTimeMillis() - start);
        log.info("ElasticsearchClient write entry to index " + indexName + ": " + (created ? "created":"updated") + ", " + duration + " ms");
        return created;
    }

    /**
     * bulk message write
     * @param jsonMapList
     *            a list of json documents to be indexed
     * @param indexName
     *            the name of the index
     * @param typeName
     *            the type of the index
     * @return a list with error messages.
     *            The key is the id of the document, the value is an error string.
     *            The method was only successful if this list is empty.
     *            This must be a list, because keys may appear several times.
     */
    @Override
    public BulkWriteResult writeMapBulk(final String indexName, final List<BulkEntry> jsonMapList) {
        while (true) try {
            return writeMapBulkInternal(indexName, jsonMapList);
        } catch (NoNodeAvailableException | IllegalStateException | ClusterBlockException | SearchPhaseExecutionException e) {
            log.info("ElasticsearchClient writeMapBulk failed with " + e.getMessage() + ", retrying to connect node...");
            try {Thread.sleep(1000);} catch (final InterruptedException ee) {}
            connect();
            continue;
        }
    }

    private BulkWriteResult writeMapBulkInternal(final String indexName, final List<BulkEntry> jsonMapList) {
        final long start = System.currentTimeMillis();
        final BulkRequestBuilder bulkRequest = this.elasticsearchClient.prepareBulk();
        for (final BulkEntry be: jsonMapList) {
            if (be.id == null) continue;
            bulkRequest.add(
                    this.elasticsearchClient.prepareIndex(indexName, be.type, be.id).setSource(be.jsonMap)
                        .setCreate(false) // enforces OpType.INDEX
                        .setVersionType(VersionType.INTERNAL));
        }
        final BulkResponse bulkResponse = bulkRequest.get();
        final BulkWriteResult result = new BulkWriteResult();
        for (final BulkItemResponse r: bulkResponse.getItems()) {
            final String id = r.getId();
            final DocWriteResponse response = r.getResponse();
            if (response == null) {
                final String err = r.getFailureMessage();
                if (err != null) {
                    result.errors.put(id, err);
                }
            } else {
                if (response.getResult() == DocWriteResponse.Result.CREATED) result.created.add(id);
            }
        }
        final long duration = Math.max(1, System.currentTimeMillis() - start);
        long regulator = 0;
        final int created = result.created.size();
        final long ops = created * 1000 / duration;
        if (duration > throttling_time_threshold && ops < throttling_ops_threshold) {
            regulator = (long) (throttling_factor * duration);
            try {Thread.sleep(regulator);} catch (final InterruptedException e) {}
        }
        log.info("ElasticsearchClient write bulk to index " + indexName + ": " + jsonMapList.size() + " entries, " + result.created.size() + " created, " + result.errors.size() + " errors, " + duration + " ms" + (regulator == 0 ? "" : ", throttled with " + regulator + " ms") + ", " + ops + " objects/second");
        return result;
    }

    private final static DateTimeFormatter utcFormatter = ISODateTimeFormat.dateTime().withZoneUTC();

    /**
     * Searches using a elasticsearch query.
     * @param indexName the name of the search index
     * @param queryBuilder a query for the search
     * @param postFilter a filter that does not affect aggregations
     * @param timezoneOffset - an offset in minutes that is applied on dates given in the query of the form since:date until:date
     * @param from - from index to start the search from, 1st entry has from-index 0.
     * @param resultCount - the number of messages in the result; can be zero if only aggregations are wanted
     * @param aggregationLimit - the maximum count of facet entities, not search results
     * @param aggregationFields - names of the aggregation fields. If no aggregation is wanted, pass no (zero) field(s)
     */
    @Override
    public FulltextIndex.Query query(final String indexName, final YaCyQuery yq, final YaCyQuery postFilter, final Sort sort, final WebMapping highlightField, int timezoneOffset, int from, int resultCount, int aggregationLimit, boolean explain, WebMapping... aggregationFields) {
        final QueryBuilder queryBuilder = yq.getQueryBuilder();
        final FulltextIndex.Query query = new FulltextIndex.Query();
        for (int t = 0; t < 10; t++) try {

            // prepare request
            SearchRequestBuilder request = ElasticsearchClient.this.elasticsearchClient.prepareSearch(indexName);
            request
                    .setExplain(explain)
                    .setSearchType(SearchType.QUERY_THEN_FETCH)
                    .setQuery(queryBuilder)
                    .setSearchType(SearchType.DFS_QUERY_THEN_FETCH) // DFS_QUERY_THEN_FETCH is slower but provides stability of search results
                    .setFrom(from)
                    .setSize(resultCount);
            if (highlightField != null) {
                final HighlightBuilder hb = new HighlightBuilder().field(highlightField.getMapping().name()).preTags("").postTags("").fragmentSize(140);
                request.highlighter(hb);
            }
            //HighlightBuilder hb = new HighlightBuilder().field("message").preTags("<foo>").postTags("<bar>");
            if (postFilter != null) request.setPostFilter(postFilter.getQueryBuilder());
            request.clearRescorers();
            for (final WebMapping field: aggregationFields) {
                request.addAggregation(AggregationBuilders.terms(field.getMapping().name()).field(field.getMapping().name()).minDocCount(1).size(aggregationLimit));
            }
            // apply sort
            request = sort.sort(request);
            // get response
            final SearchResponse response = request.execute().actionGet();
            final SearchHits searchHits = response.getHits();
            query.hitCount = (int) searchHits.getTotalHits();

            // evaluate search result
            //long totalHitCount = response.getHits().getTotalHits();
            final SearchHit[] hits = searchHits.getHits();
            query.results = new ArrayList<Map<String, Object>>(query.hitCount);
            query.explanations = new ArrayList<String>(query.hitCount);
            query.highlights = new ArrayList<Map<String, HighlightField>>(query.hitCount);
            for (final SearchHit hit: hits) {
                final Map<String, Object> map = hit.getSourceAsMap();
                if (!map.containsKey("id")) map.put("id", hit.getId());
                if (!map.containsKey("type")) map.put("type", hit.getType());
                query.results.add(map);
                query.highlights.add(hit.getHighlightFields());
                if (explain) {
                    final Explanation explanation = hit.getExplanation();
                    query.explanations.add(explanation.toString());
                } else {
                    query.explanations.add("");
                }
            }

            // evaluate aggregation
            // collect results: fields
            query.aggregations = new HashMap<>();
            for (final WebMapping field: aggregationFields) {
                final Terms fieldCounts = response.getAggregations().get(field.getMapping().name());
                final List<? extends Bucket> buckets = fieldCounts.getBuckets();
                // aggregate double-tokens (matching lowercase)
                final Map<String, Long> checkMap = new HashMap<>();
                for (final Bucket bucket: buckets) {
                    final String key = bucket.getKeyAsString().trim();
                    if (key.length() > 0) {
                        final String k = key.toLowerCase();
                        final Long v = checkMap.get(k);
                        checkMap.put(k, v == null ? bucket.getDocCount() : v + bucket.getDocCount());
                    }
                }
                final ArrayList<Map.Entry<String, Long>> list = new ArrayList<>(buckets.size());
                for (final Bucket bucket: buckets) {
                    final String key = bucket.getKeyAsString().trim();
                    if (key.length() > 0) {
                        final Long v = checkMap.remove(key.toLowerCase());
                        if (v == null) continue;
                        list.add(new AbstractMap.SimpleEntry<String, Long>(key, v));
                    }
                }
                query.aggregations.put(field.getMapping().name(), list);
                //if (field.equals("place_country")) {
                    // special handling of country aggregation: add the country center as well
                //}
            }
            return query;

        } catch (NoNodeAvailableException | IllegalStateException | ClusterBlockException | SearchPhaseExecutionException e) {
            log.warn("ElasticsearchClient query failed with " + e.getMessage() + ", retrying attempt " + t + " ...", e);
            try {Thread.sleep(1000);} catch (final InterruptedException eee) {}
            connect();
            continue;
        }
        return query;
    }

    @SuppressWarnings("unused")
    private List<Map<String, Object>> queryWithConstraints(final String indexName, final String fieldName, final String fieldValue, final Map<String, String> constraints, boolean latest) throws IOException {
        final SearchRequestBuilder request = this.elasticsearchClient.prepareSearch(indexName)
                .setSearchType(SearchType.QUERY_THEN_FETCH)
                .setFrom(0);

        final BoolQueryBuilder bFilter = QueryBuilders.boolQuery();
        bFilter.must(QueryBuilders.constantScoreQuery(QueryBuilders.constantScoreQuery(QueryBuilders.termQuery(fieldName, fieldValue))));
        for (final Object o : constraints.entrySet()) {
            @SuppressWarnings("rawtypes")
            final
            Map.Entry entry = (Map.Entry) o;
            bFilter.must(QueryBuilders.constantScoreQuery(QueryBuilders.termQuery((String) entry.getKey(), ((String) entry.getValue()).toLowerCase())));
        }
        request.setQuery(bFilter);

        // get response
        final SearchResponse response = request.execute().actionGet();

        // evaluate search result
        final ArrayList<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        final SearchHit[] hits = response.getHits().getHits();
        for (final SearchHit hit: hits) {
            final Map<String, Object> map = hit.getSourceAsMap();
            result.add(map);
        }

        return result;
    }

    public static void main(String[] args) {
        final ElasticsearchClient client = new ElasticsearchClient(new String[]{"localhost:9300"}, "");
        // check access
        client.createIndexIfNotExists("test", 1, 0);
        System.out.println(client.count("test"));
        // upload a schema
        try {
            final String mapping = new String(Files.readAllBytes(Paths.get("conf/mappings/web.json")));
            client.setMapping("test", mapping);
        } catch (final IOException e) {
            log.warn("", e);
        }

        client.close();
    }

}
