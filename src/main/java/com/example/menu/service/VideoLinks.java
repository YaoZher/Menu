package com.example.menu.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts a web link from pasted sharing text without visiting the remote site. */
final class VideoLinks {
    private static final Pattern LINK = Pattern.compile(
            "https?://[^\\s<>\"'`，。！？；：、（）【】《》“”‘’「」『』]*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    private VideoLinks() { }

    static String extract(String value) {
        if (value == null) return null;
        if (value.length() > 8192) throw ApiException.badRequest("视频分享内容不能超过 8192 个字符");
        if (value.isBlank()) return null;
        Matcher matcher = LINK.matcher(value);
        if (matcher.find()) {
            if (!startsLink(value, matcher.start())) throw ApiException.badRequest("视频链接格式不正确");
            String candidate = trimPunctuation(matcher.group());
            if (candidate.length() > 2048) throw ApiException.badRequest("视频链接不能超过 2048 个字符");
            validate(candidate);
            return candidate;
        }
        throw ApiException.badRequest("未找到视频链接，请粘贴含 http:// 或 https:// 的分享内容");
    }

    private static boolean startsLink(String value, int start) {
        if (start == 0) return true;
        char previous = value.charAt(start - 1);
        if (previous == ':') {
            return start == 1 || Character.isWhitespace(value.charAt(start - 2)) || value.charAt(start - 2) > 127;
        }
        return Character.isWhitespace(previous) || previous > 127 || "([{<\"'`".indexOf(previous) >= 0;
    }

    private static String trimPunctuation(String candidate) {
        while (!candidate.isEmpty()) {
            char last = candidate.charAt(candidate.length() - 1);
            if (".,!?;:".indexOf(last) >= 0 || unpairedClosing(candidate, last)) {
                candidate = candidate.substring(0, candidate.length() - 1);
            } else break;
        }
        return candidate;
    }

    private static boolean unpairedClosing(String value, char closing) {
        int index = ")]}".indexOf(closing);
        if (index < 0) return false;
        char opening = "([{".charAt(index);
        long closed = value.chars().filter(c -> c == closing).count();
        long opened = value.chars().filter(c -> c == opening).count();
        return closed > opened;
    }

    private static void validate(String value) {
        try {
            URI uri = new URI(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null
                    || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))) {
                throw ApiException.badRequest("视频链接须为有效的 http 或 https 网址，且不能包含账号密码");
            }
        } catch (URISyntaxException exception) {
            throw ApiException.badRequest("视频链接格式不正确");
        }
    }
}
