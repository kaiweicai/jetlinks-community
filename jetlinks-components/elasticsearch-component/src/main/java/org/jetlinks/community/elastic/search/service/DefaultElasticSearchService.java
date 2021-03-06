package org.jetlinks.community.elastic.search.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.core.CountRequest;
import org.elasticsearch.client.core.CountResponse;
import org.elasticsearch.common.xcontent.XContentType;
import org.hswebframework.ezorm.core.param.QueryParam;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.jetlinks.core.utils.FluxUtils;
import org.jetlinks.community.elastic.search.ElasticRestClient;
import org.jetlinks.community.elastic.search.index.ElasticIndex;
import org.jetlinks.community.elastic.search.index.mapping.IndexMappingMetadata;
import org.jetlinks.community.elastic.search.parser.QueryParamTranslateService;
import org.reactivestreams.Publisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;

import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author bsetfeng
 * @since 1.0
 **/
@Service
@Slf4j
public class DefaultElasticSearchService implements ElasticSearchService {

    private final ElasticRestClient restClient;

    private final IndexOperationService indexOperationService;

    private final QueryParamTranslateService translateService;

    FluxSink<Buffer> sink;

    public DefaultElasticSearchService(ElasticRestClient restClient,
                                       QueryParamTranslateService translateService,
                                       IndexOperationService indexOperationService) {
        this.restClient = restClient;
        this.translateService = translateService;
        this.indexOperationService = indexOperationService;
        init();
    }


    @Override
    public <T> Mono<PagerResult<T>> queryPager(ElasticIndex index, QueryParam queryParam, Class<T> type) {
        return query(searchRequestStructure(queryParam, index))
            .map(response -> translatePageResult(type, queryParam, response))
            .switchIfEmpty(Mono.just(PagerResult.empty()))
            .onErrorReturn(err -> {
                log.error("query elastic error", err);
                return true;
            }, PagerResult.empty());
    }

    @Override
    public <T> Flux<T> query(ElasticIndex index, QueryParam queryParam, Class<T> type) {
        return query(searchRequestStructure(queryParam, index))
            .flatMapIterable(response -> translate(type, response))
            .onErrorResume(err -> {
                log.error("query elastic error", err);
                return Flux.empty();
            });
    }

    @Override
    public Mono<Long> count(ElasticIndex index, QueryParam queryParam) {
        return countQuery(countRequestStructure(queryParam, index))
            .map(CountResponse::getCount)
            .defaultIfEmpty(0L)
            .onErrorReturn(err -> {
                log.error("query elastic error", err);
                return true;
            }, 0L);
    }


    @Override
    public <T> Mono<Void> commit(ElasticIndex index, T payload) {
        return Mono.fromRunnable(() -> {
            sink.next(new Buffer(index, payload));
        });
    }

    @Override
    public <T> Mono<Void> commit(ElasticIndex index, Collection<T> payload) {
        return Mono.fromRunnable(() -> {
            for (T t : payload) {
                sink.next(new Buffer(index, t));
            }
        });
    }

    @Override
    public <T> Mono<Void> commit(ElasticIndex index, Publisher<T> data) {
        return Flux.from(data)
            .flatMap(d -> commit(index, d))
            .then();
    }

    @PreDestroy
    public void shutdown() {
        sink.complete();
    }

    //@PostConstruct
    public void init() {
        //这里的警告都输出到控制台,输入到slf4j可能会造成日志递归.
        FluxUtils.bufferRate(
            Flux.<Buffer>create(sink -> this.sink = sink),
            1000,
            2000,
            Duration.ofSeconds(3))
            .onBackpressureBuffer(512,
                drop -> System.err.println("无法处理更多索引请求!"),
                BufferOverflowStrategy.DROP_OLDEST)
            .flatMap(this::doSave)
            .doOnNext((len) -> {
                if (log.isDebugEnabled() && len > 0) {
                    log.debug("保存ElasticSearch数据成功,数量:{}", len);
                }
            })
            .onErrorContinue((err, obj) -> System.err.println("保存ElasticSearch数据失败:\n" + org.hswebframework.utils.StringUtils.throwable2String(err)))
            .subscribe();
    }

    @AllArgsConstructor
    @Getter
    static class Buffer {
        ElasticIndex index;
        Object payload;
    }


    protected Mono<Integer> doSave(Collection<Buffer> buffers) {
        return Flux.fromIterable(buffers)
            .collect(Collectors.groupingBy(Buffer::getIndex))
            .map(Map::entrySet)
            .flatMapIterable(Function.identity())
            .map(entry -> {
                ElasticIndex index = entry.getKey();
                BulkRequest bulkRequest = new BulkRequest(index.getStandardIndex(), index.getStandardType());
                for (Buffer buffer : entry.getValue()) {
                    IndexRequest request = new IndexRequest();
                    Object o = JSON.toJSON(buffer.getPayload());
                    if (o instanceof Map) {
                        request.source((Map) o);
                    } else {
                        request.source(o.toString(), XContentType.JSON);
                    }
                    bulkRequest.add(request);
                }
                entry.getValue().clear();
                return bulkRequest;
            })
            .flatMap(bulkRequest ->
                Mono.<Integer>create(sink ->
                    restClient.getWriteClient()
                        .bulkAsync(bulkRequest, RequestOptions.DEFAULT, new ActionListener<BulkResponse>() {
                            @Override
                            public void onResponse(BulkResponse responses) {
                                if (responses.hasFailures()) {
                                    sink.error(new RuntimeException("保存ElasticSearch数据失败:" + responses.buildFailureMessage()));
                                    return;
                                }
                                sink.success(buffers.size());
                            }

                            @Override
                            public void onFailure(Exception e) {
                                sink.error(e);
                            }
                        })))
            .collect(Collectors.summingInt(Integer::intValue));
    }

    private <T> PagerResult<T> translatePageResult(Class<T> clazz, QueryParam param, SearchResponse response) {
        long total = response.getHits().getTotalHits();
        return PagerResult.of((int) total, translate(clazz, response), param);
    }

    private <T> List<T> translate(Class<T> clazz, SearchResponse response) {
        // TODO: 2020/1/18 临时代码
        return Arrays.stream(response.getHits().getHits())
            .map(hit -> {
                Map<String, Object> hitMap = hit.getSourceAsMap();
                hitMap.put("id", hit.getId());
                return JSON.toJavaObject(new JSONObject(hitMap), clazz);
            })
            .collect(Collectors.toList());
    }

    private Mono<SearchResponse> query(Mono<SearchRequest> requestMono) {
        return requestMono.<SearchResponse>flatMap((request) ->
            Mono.create(sink -> restClient
                .getQueryClient()
                .searchAsync(request, RequestOptions.DEFAULT, translatorActionListener(sink))))
            .onErrorResume(err -> {
                log.error("query elastic error", err);
                return Mono.empty();
            });
    }

    private Mono<CountResponse> countQuery(Mono<CountRequest> requestMono) {
        return requestMono.<CountResponse>flatMap((request) ->
            Mono.create(sink -> restClient
                .getQueryClient()
                .countAsync(request, RequestOptions.DEFAULT, translatorActionListener(sink))))
            .onErrorResume(err -> {
                log.error("query elastic error", err);
                return Mono.empty();
            });
    }

    private <T> ActionListener<T> translatorActionListener(MonoSink<T> sink) {
        return new ActionListener<T>() {
            @Override
            public void onResponse(T response) {
                sink.success(response);
            }

            @Override
            public void onFailure(Exception e) {
                if (e instanceof ElasticsearchException) {
                    if (((ElasticsearchException) e).status().getStatus() == 404) {
                        sink.success();
                        return;
                    } else if (((ElasticsearchException) e).status().getStatus() == 400) {
                        sink.error(new ElasticsearchParseException("查询参数格式错误", e));
                    }
                }
                sink.error(e);
            }
        };
    }

    private Mono<SearchRequest> searchRequestStructure(QueryParam queryParam, ElasticIndex provider) {
        return indexOperationService.getIndexMappingMetadata(provider.getStandardIndex())
            .switchIfEmpty(Mono.just(IndexMappingMetadata.getInstance(provider.getStandardIndex())))
            .map(metadata -> {
                SearchRequest request = new SearchRequest(provider.getStandardIndex())
                    .source(translateService.translate(queryParam, metadata));
                if (StringUtils.hasText(provider.getStandardType())) {
                    request.types(provider.getStandardType());
                }
                return request;
            })
            .doOnNext(searchRequest -> log.debug("查询index：{},es查询参数:{}", provider.getStandardIndex(), searchRequest.source().toString()))
            .onErrorResume(err -> {
                log.error("query index error", err);
                return Mono.empty();
            });
    }

    private Mono<CountRequest> countRequestStructure(QueryParam queryParam, ElasticIndex provider) {
        QueryParam tempQueryParam = queryParam.clone();
        tempQueryParam.setPaging(false);
        tempQueryParam.setSorts(Collections.emptyList());
        return indexOperationService.getIndexMappingMetadata(provider.getStandardIndex())
            .map(metadata -> new CountRequest(provider.getStandardIndex())
                .source(translateService.translate(tempQueryParam, metadata)))
            .onErrorResume(err -> {
                log.error("query index error", err);
                return Mono.empty();
            });
    }
}
