package com.example.menu.domain;

import jakarta.persistence.*;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** A separate ordering record keeps presentation changes out of dish review versions. */
@Entity // 菜单订单实体
@Table(name = "menu_orders")
public class MenuOrder {
    public static final long SINGLETON_ID = 1L;

    @Id private Long id;
    @Lob @Column(name = "ordered_ids") private String orderedIds;
    //注释:@Lob 表示该字段将被映射为数据库中的大对象（Large Object），通常用于存储大量文本或二进制数据。
    @Column(nullable = false) private long revision;
    @Version @Column(name = "lock_version", nullable = false) private long lockVersion;

    protected MenuOrder() { }
    public MenuOrder(long id) { this.id = id; }
    public boolean isConfigured() { return orderedIds != null; }
    public long getRevision() { return revision; }
    public List<Long> dishIds() {
        if (orderedIds == null || orderedIds.isEmpty()) return List.of();
        return Arrays.stream(orderedIds.split(",")).map(Long::valueOf).toList();
    }
    public void replace(List<Long> ids) {
        orderedIds = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        revision++;
    }
}
