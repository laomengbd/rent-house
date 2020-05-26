package com.harry.renthouse.service.search.impl;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.harry.renthouse.base.ApiResponseEnum;
import com.harry.renthouse.base.HouseSortOrderByEnum;
import com.harry.renthouse.elastic.entity.HouseElastic;
import com.harry.renthouse.elastic.entity.HouseKafkaMessage;
import com.harry.renthouse.elastic.entity.HouseSuggestion;
import com.harry.renthouse.elastic.key.HouseElasticKey;
import com.harry.renthouse.elastic.repository.HouseElasticRepository;
import com.harry.renthouse.entity.House;
import com.harry.renthouse.entity.HouseDetail;
import com.harry.renthouse.entity.HouseTag;
import com.harry.renthouse.exception.BusinessException;
import com.harry.renthouse.repository.HouseDetailRepository;
import com.harry.renthouse.repository.HouseRepository;
import com.harry.renthouse.repository.HouseTagRepository;
import com.harry.renthouse.service.ServiceMultiResult;
import com.harry.renthouse.service.search.HouseElasticSearchService;
import com.harry.renthouse.web.form.SearchHouseForm;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.action.admin.indices.analyze.AnalyzeAction;
import org.elasticsearch.action.admin.indices.analyze.AnalyzeRequestBuilder;
import org.elasticsearch.action.admin.indices.analyze.AnalyzeResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.search.suggest.Suggest;
import org.elasticsearch.search.suggest.SuggestBuilder;
import org.elasticsearch.search.suggest.SuggestBuilders;
import org.elasticsearch.search.suggest.completion.CompletionSuggestion;
import org.elasticsearch.search.suggest.completion.CompletionSuggestionBuilder;
import org.modelmapper.ModelMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Harry Xu
 * @date 2020/5/20 13:35
 */
@Service
@Slf4j
public class HouseElasticSearchServiceImpl implements HouseElasticSearchService {

    private static final String HOUSE_INDEX_TOPIC = "HOUSE_INDEX_TOPIC2";

    private static final String IK_SMART = "IK_SMART";

    private static final String INDEX_NAME = "rent-house";

    public static final int DEFAULT_SUGGEST_SIZE = 5;


    @Resource
    private HouseElasticRepository houseElasticRepository;

    @Resource
    private HouseRepository houseRepository;

    @Resource
    private HouseDetailRepository houseDetailRepository;

    @Resource
    private ModelMapper modelMapper;

    @Resource
    private HouseTagRepository houseTagRepository;

    @Resource
    private KafkaTemplate<String, String> kafkaTemplate;

    @Resource
    private Gson gson;

    @Resource
    private ElasticsearchRestTemplate elasticsearchRestTemplate;

    @KafkaListener(topics = HOUSE_INDEX_TOPIC)
    public void handleMessage(String message){
        try{
            HouseKafkaMessage houseKafkaMessage = gson.fromJson(message, HouseKafkaMessage.class);
            switch (houseKafkaMessage.getOperation()){
                case HouseKafkaMessage.INDEX:
                    kafkaSave(houseKafkaMessage);
                    break;
                case HouseKafkaMessage.DELETE:
                    kafkaDelete(houseKafkaMessage);
                    break;
                default:
            }
        }catch (JsonSyntaxException e){
            log.error("解析消息体失败: {}", message, e);
        }
    }

    private void kafkaSave(HouseKafkaMessage houseKafkaMessage){
        Long houseId = houseKafkaMessage.getId();
        HouseElastic houseElastic = new HouseElastic();
        House house = houseRepository.findById(houseId).orElseThrow(() -> new BusinessException(ApiResponseEnum.HOUSE_NOT_FOUND_ERROR));
        // 映射房屋数据
        modelMapper.map(house, houseElastic);
        // 映射房屋详情
        HouseDetail houseDetail = houseDetailRepository.findByHouseId(houseId).orElseThrow(() -> new BusinessException(ApiResponseEnum.HOUSE_DETAIL_NOT_FOUND_ERROR));
        modelMapper.map(houseDetail, houseElastic);
        // 映射标签信息
        List<HouseTag> tags = houseTagRepository.findAllByHouseId(houseId);
        List<String> tagList = tags.stream().map(HouseTag::getName).collect(Collectors.toList());
        houseElastic.setTags(tagList);
        // 设置推荐词
        updateSuggests(houseElastic);
        // 存储至elastic中
        houseElasticRepository.save(houseElastic);
    }

    private void updateSuggests(HouseElastic houseElastic){
        // 对关键词进行分析
        // todo 需要对相关描述进行分词
        /*AnalyzeRequestBuilder analyzeRequestBuilder = new AnalyzeRequestBuilder(
                elasticsearchClient,
                AnalyzeAction.INSTANCE, INDEX_NAME,
                houseElastic.getTitle(), houseElastic.getLayoutDesc(),
                houseElastic.getRoundService(),
                houseElastic.getDescription());
        analyzeRequestBuilder.setAnalyzer(IK_SMART);
        // 处理分析结果
        AnalyzeResponse response = analyzeRequestBuilder.get();
        List<AnalyzeResponse.AnalyzeToken> tokens = response.getTokens();
        if(tokens == null){
            log.warn("无法对当前房源关键词进行分析:" + houseElastic);
            throw new BusinessException(ApiResponseEnum.ELASTIC_HOUSE_SUGGEST_CREATE_ERROR);
        }
        List<HouseSuggestion> suggestionList = tokens.stream().filter(token -> !StringUtils.equals("<NUM>", token.getType())
                && StringUtils.isNotBlank(token.getTerm()) && token.getTerm().length() > 2).map(item -> {
            HouseSuggestion houseSuggestion = new HouseSuggestion();
            houseSuggestion.setInput(item.getTerm());
            return houseSuggestion;
        }).collect(Collectors.toList());
        log.debug("包括对象------------------------");*/
        List<HouseSuggestion> suggestionList = new ArrayList<>();
        suggestionList.add(new HouseSuggestion(houseElastic.getTitle(), 30));
        suggestionList.add(new HouseSuggestion(houseElastic.getDistrict(), 20));
        suggestionList.add(new HouseSuggestion(houseElastic.getSubwayLineName(), 15));
        suggestionList.add(new HouseSuggestion(houseElastic.getSubwayStationName(), 15));
        houseElastic.setSuggests(suggestionList);
    }

    private void kafkaDelete(HouseKafkaMessage houseKafkaMessage){
        Long houseId = houseKafkaMessage.getId();
        houseElasticRepository.findById(houseId).orElseThrow(() -> new BusinessException(ApiResponseEnum.ELASTIC_HOUSE_NOT_FOUND));
        houseElasticRepository.deleteById(houseId);
    }

    @Override
    public void save(Long houseId) {
        HouseKafkaMessage houseKafkaMessage = new HouseKafkaMessage(houseId, HouseKafkaMessage.INDEX, 0);
        kafkaTemplate.send(HOUSE_INDEX_TOPIC, gson.toJson(houseKafkaMessage));
    }

    @Override
    public void delete(Long houseId) {
        HouseKafkaMessage houseKafkaMessage = new HouseKafkaMessage(houseId, HouseKafkaMessage.DELETE, 0);
        kafkaTemplate.send(HOUSE_INDEX_TOPIC, gson.toJson(houseKafkaMessage));
    }

    @Override
    public ServiceMultiResult<Long> search(SearchHouseForm searchHouseForm) {
        NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
        boolQueryBuilder.filter(QueryBuilders.termQuery(HouseElasticKey.CITY_EN_NAME, searchHouseForm.getCityEnName()));
        // 查询区域
        if(StringUtils.isNotBlank(searchHouseForm.getRegionEnName())){
            boolQueryBuilder.filter(QueryBuilders.termQuery(HouseElasticKey.REGION_EN_NAME, searchHouseForm.getRegionEnName()));
        }
        // 查询关键字
        boolQueryBuilder.must(QueryBuilders.multiMatchQuery(searchHouseForm.getKeyword(),
                HouseElasticKey.TITLE,
                HouseElasticKey.TRAFFIC,
                HouseElasticKey.DISTRICT,
                HouseElasticKey.ROUND_SERVICE,
                HouseElasticKey.SUBWAY_LINE_NAME,
                HouseElasticKey.SUBWAY_STATION_NAME
                ));
        // 查询面积区间
        if(searchHouseForm.getAreaMin() != null || searchHouseForm.getAreaMax() != null){
            RangeQueryBuilder rangeQueryBuilder = QueryBuilders.rangeQuery(HouseElasticKey.AREA);
            if(searchHouseForm.getAreaMin() > 0){
                rangeQueryBuilder.gte(searchHouseForm.getAreaMin());
            }
            if(searchHouseForm.getAreaMax() > 0){
                rangeQueryBuilder.lte(searchHouseForm.getAreaMax());
            }
        }
        // 查询价格区间
        if(searchHouseForm.getPriceMin() != null || searchHouseForm.getPriceMax() != null){
            RangeQueryBuilder rangeQueryBuilder = QueryBuilders.rangeQuery(HouseElasticKey.PRICE);
            if(searchHouseForm.getPriceMin() != null && searchHouseForm.getPriceMin() > 0){
                rangeQueryBuilder.gte(searchHouseForm.getPriceMin());
            }
            if(searchHouseForm.getPriceMax() != null && searchHouseForm.getPriceMax() > 0){
                rangeQueryBuilder.lte(searchHouseForm.getPriceMax());
            }
            boolQueryBuilder.filter(rangeQueryBuilder);
        }
        // 房屋朝向
        if(searchHouseForm.getDirection() != null && searchHouseForm.getDirection() > 0){
            boolQueryBuilder.filter(QueryBuilders.termQuery(HouseElasticKey.DIRECTION, searchHouseForm.getDirection()));
        }
        // 出租方式
        if(searchHouseForm.getRentWay() != null && searchHouseForm.getRentWay() >= 0){
            boolQueryBuilder.filter(QueryBuilders.termQuery(HouseElasticKey.RENT_WAY, searchHouseForm.getRentWay()));
        }
        queryBuilder.withQuery(boolQueryBuilder);
        queryBuilder.withSort(SortBuilders.fieldSort(HouseSortOrderByEnum
                .from(searchHouseForm.getOrderBy())
                .orElse(HouseSortOrderByEnum.DEFAULT).getValue())
                .order(SortOrder.fromString(searchHouseForm.getSortDirection())));
        Pageable pageable = PageRequest.of(searchHouseForm.getPage() - 1, searchHouseForm.getPageSize());
        queryBuilder.withPageable(pageable);
        Page<HouseElastic> page = houseElasticRepository.search(queryBuilder.build());
        int total = (int) page.getTotalElements();
        List<Long> result = page.getContent().stream().map(HouseElastic::getHouseId).collect(Collectors.toList());
        return new ServiceMultiResult<>(total, result);
    }

    @Override
    public ServiceMultiResult<String> suggest(String prefix) {
        return suggest(prefix, DEFAULT_SUGGEST_SIZE);
    }

    @Override
    public ServiceMultiResult<String> suggest(String prefix, int size) {
        // 构建推荐查询
        CompletionSuggestionBuilder suggestionBuilder = SuggestBuilders.completionSuggestion(HouseElasticKey.SUGGESTS)
                .prefix(prefix).size(size);
        SuggestBuilder suggestBuilders = new SuggestBuilder();
        suggestBuilders.addSuggestion("autoComplete", suggestionBuilder);
        // 获取查询响应结果
        SearchResponse response = elasticsearchRestTemplate.suggest(suggestBuilders, HouseElastic.class);
        Suggest suggest = response.getSuggest();
        Suggest.Suggestion result = suggest.getSuggestion("autoComplete");

        // 构造推荐结果集
        Set<String> suggestSet = new HashSet<>();
        for (Object term : result.getEntries()) {
            if(term instanceof CompletionSuggestion.Entry){
                CompletionSuggestion.Entry item = (CompletionSuggestion.Entry) term;
                // 如果option不为空
                if(!CollectionUtils.isEmpty(item.getOptions())){
                    for (CompletionSuggestion.Entry.Option option : item.getOptions()) {
                        String tip = option.getText().string();
                        suggestSet.add(tip);
                        if(suggestSet.size() >= size){
                            break;
                        }
                    }
                }
            }
            if(suggestSet.size() >= size){
                break;
            }
        }
        List<String> list = Arrays.asList(suggestSet.toArray(new String[0]));
        return new ServiceMultiResult<>(list.size(), list);
    }

}