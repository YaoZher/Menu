package com.example.menu.service;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VideoLinksTest {
    @ParameterizedTest
    @MethodSource("shareLinks")
    void extractsLinkAndPreservesItsPathQueryAndFragment(String text, String expected) {
        assertThat(VideoLinks.extract(text)).isEqualTo(expected);
    }

    static Stream<Arguments> shareLinks() {
        return Stream.of(
                Arguments.of("https://www.bilibili.com/video/BV123/?p=2#reply", "https://www.bilibili.com/video/BV123/?p=2#reply"),
                Arguments.of("3.21 复制打开抖音，看看这道菜！https://v.douyin.com/Abc123/ 10/27", "https://v.douyin.com/Abc123/"),
                Arguments.of("【红烧肉】https://b23.tv/Abc123。", "https://b23.tv/Abc123"),
                Arguments.of("链接:https://b23.tv/AbC", "https://b23.tv/AbC"),
                Arguments.of("分享给你\nhttps://www.xiaohongshu.com/explore/123?xsec_token=abc%2Fdef%3D&xsec_source=pc_share\n点击查看", "https://www.xiaohongshu.com/explore/123?xsec_token=abc%2Fdef%3D&xsec_source=pc_share"),
                Arguments.of("微信分享：https://mp.weixin.qq.com/s?__biz=abc&mid=123#wechat_redirect，点击查看", "https://mp.weixin.qq.com/s?__biz=abc&mid=123#wechat_redirect"),
                Arguments.of("（https://example.com/菜谱?口味=清淡#步骤）", "https://example.com/菜谱?口味=清淡#步骤"),
                Arguments.of("链接（https://example.com/recipe_(easy)）。", "https://example.com/recipe_(easy)"),
                Arguments.of("(https://example.com/recipe_(easy)).", "https://example.com/recipe_(easy)"),
                Arguments.of("[https://example.com/watch?id=1&token=a%26b#part2]", "https://example.com/watch?id=1&token=a%26b#part2"),
                Arguments.of("『HTTPS://EXAMPLE.COM/watch』", "HTTPS://EXAMPLE.COM/watch"),
                Arguments.of("http://example.com:8080/watch;", "http://example.com:8080/watch"),
                Arguments.of("第一条 https://b23.tv/first 第二条 https://v.douyin.com/second", "https://b23.tv/first"),
                Arguments.of("https://[2001:db8::1]/watch", "https://[2001:db8::1]/watch"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "没有链接的文案", "javascript:alert(1)", "javascript:https://example.com", "ftp://https://example.com",
            "https://name:pass@example.com", "https://bad_host/watch", "https:///missing-host", "https://example.com:0/watch",
            "https://example.com:65536/watch", "https://example.com/%zz", "https://example.com\\other",
            "https://name:pass@example.com https://example.com/valid", "https:// https://example.com/valid",
            "javascript:https://example.com https://b23.tv/valid"
    })
    void rejectsUnsafeInvalidAndMissingLinks(String text) {
        assertThatThrownBy(() -> VideoLinks.extract(text)).isInstanceOf(ApiException.class);
    }

    @Test void limitsShareTextSeparatelyFromExtractedUrl() {
        String url = "https://example.com/" + "a".repeat(2028);
        assertThat(url).hasSize(2048);
        assertThat(VideoLinks.extract("分享：" + url + " 请查看")).isEqualTo(url);
        assertThatThrownBy(() -> VideoLinks.extract(url + "a")).isInstanceOf(ApiException.class);
        assertThat(VideoLinks.extract("文".repeat(3000) + " https://b23.tv/abc")).isEqualTo("https://b23.tv/abc");
        assertThatThrownBy(() -> VideoLinks.extract(" ".repeat(8193))).isInstanceOf(ApiException.class);
    }

    @Test void videoRemainsOptional() {
        assertThat(VideoLinks.extract(null)).isNull();
        assertThat(VideoLinks.extract(" \n\t ")).isNull();
    }
}
