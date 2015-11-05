package com.mparticle.iterable;


import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public class TrackPurchaseRequest {
    public ApiUser user;
    public List<CommerceItem> items;
    public Integer campaignId;
    public Integer templateId;
    public BigDecimal total;
    public Integer createdAt;
    public Map<String, String> dataFields;
}
