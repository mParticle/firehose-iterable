package com.mparticle.iterable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public class CommerceItem {
    public String id;
    public String sku;
    public String name;
    public String description;
    public List<String> categories;
    public BigDecimal price;
    //iterable will error if this is not present
    public Integer quantity = 1;
    public String imageUrl;
    public Map<String, String> dataFields;

}
