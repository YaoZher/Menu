package com.example.menu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:menutest;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.chef.password=Test-password-2026!",
    "app.rate-limit.enabled=false"
})
@AutoConfigureMockMvc
class MenuIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test void publicReadsAndPrivateReviewEndpoints() throws Exception {
        mvc.perform(get("/api/menu")).andExpect(status().isOk());
        mvc.perform(get("/chef/login")).andExpect(status().isOk()).andExpect(forwardedUrl("/login.html"));
        mvc.perform(get("/api/chef/submissions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/chef")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/chef/login"));
        mvc.perform(get("/chef.html")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/chef").with(chef())).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/api/chef/me").with(user("visitor").roles("USER"))).andExpect(status().isForbidden());
    }

    @Test void formerAdminIdentityAndRoutesNoLongerGrantAccess() throws Exception {
        mvc.perform(post("/api/chef/login").with(csrf())
                .param("username", "admin").param("password", "Test-password-2026!"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/chef/me").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        for (String route : new String[] { "/admin", "/admin/", "/admin.html" }) {
            mvc.perform(get(route)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/chef/login"));
            mvc.perform(get(route).with(chef())).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/admin/submissions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/submissions").with(chef())).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").with(csrf())
                .param("username", "chef").param("password", "Test-password-2026!"))
                .andExpect(status().isUnauthorized());
    }

    @Test void csrfRequiredForAnonymousSubmissionsAndReview() throws Exception {
        mvc.perform(multipart("/api/submissions").param("operation", "CREATE").param("name", "豆腐"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/chef/submissions/1/approve").with(chef()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/chef/login").param("username", "chef").param("password", "Test-password-2026!"))
                .andExpect(status().isForbidden());
    }

    @Test void realSessionLoginRefreshesCsrfAndLogoutProtectsReview() throws Exception {
        MvcResult csrfResult = mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) csrfResult.getRequest().getSession(false);
        JsonNode token = json(csrfResult);
        mvc.perform(post("/api/chef/login").session(session)
                .header(token.path("headerName").asText(), token.path("token").asText())
                .param("username", "chef").param("password", "Test-password-2026!"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("chef"));
        mvc.perform(get("/api/chef/me").session(session)).andExpect(status().isOk());
        JsonNode refreshed = json(mvc.perform(get("/api/csrf").session(session)).andReturn());
        mvc.perform(post("/api/chef/logout").session(session)
                .header(refreshed.path("headerName").asText(), refreshed.path("token").asText())).andExpect(status().isOk());
        mvc.perform(get("/api/chef/me")).andExpect(status().isUnauthorized());
    }

    @Test void wrongPasswordReturnsJsonWithoutLoggingIn() throws Exception {
        mvc.perform(post("/api/chef/login").with(csrf()).param("username", "chef").param("password", "wrong"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").exists());
    }

    @Test void createWaitsForApprovalAndCannotBeApprovedTwice() throws Exception {
        String name = "审核后上架-" + System.nanoTime();
        long submission = submit(create(name));
        assertThat(findDish(name)).isNull();
        approve(submission, 200);
        assertThat(findDish(name)).isNotNull();
        approve(submission, 409);
    }

    @Test void rejectedSubmissionNeverEntersMenu() throws Exception {
        String name = "驳回-" + System.nanoTime();
        long id = submit(create(name));
        mvc.perform(post("/api/chef/submissions/{id}/reject", id).with(chef()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"请补充菜品信息\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reviewedBy").value("reviewer"));
        assertThat(findDish(name)).isNull();
        approve(id, 409);
    }

    @Test void editsAndDeletesOnlyApplyAfterApproval() throws Exception {
        String original = "原菜-" + System.nanoTime();
        JsonNode dish = published(original);
        String updated = original + "改";
        long edit = submit(change("UPDATE", dish).param("name", updated).param("notes", "少盐"));
        assertThat(findDish(original)).isNotNull();
        JsonNode beforeReview = json(mvc.perform(get("/api/chef/submissions?status=ALL").with(chef())).andReturn());
        JsonNode application = null;
        for (JsonNode n : beforeReview) if (n.path("id").asLong() == edit) application = n;
        assertThat(application).isNotNull();
        assertThat(application.path("before").path("name").asText()).isEqualTo(original);
        assertThat(application.path("after").path("name").asText()).isEqualTo(updated);
        approve(edit, 200);
        assertThat(findDish(original)).isNull();
        JsonNode edited = findDish(updated);
        assertThat(edited.path("notes").asText()).isEqualTo("少盐");
        long deletion = submit(change("DELETE", edited));
        assertThat(findDish(updated)).isNotNull();
        approve(deletion, 200);
        assertThat(findDish(updated)).isNull();
    }

    @Test void staleParallelEditsCannotOverwritePublishedChanges() throws Exception {
        String name = "并发-" + System.nanoTime();
        JsonNode dish = published(name);
        long a = submit(change("UPDATE", dish).param("name", name + "A"));
        long b = submit(change("UPDATE", dish).param("name", name + "B"));
        approve(a, 200);
        approve(b, 409);
        assertThat(findDish(name + "A")).isNotNull();
        assertThat(findDish(name + "B")).isNull();
        mvc.perform(change("DELETE", dish).with(csrf())).andExpect(status().isConflict());
    }

    @Test void rejectsInvalidInputAndUnsafeVideoLinks() throws Exception {
        mvc.perform(create("  ").with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(create("菜".repeat(81)).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(create("测试").param("videoUrl", "javascript:alert(1)").with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(create("测试").param("videoUrl", "https://name:pass@example.com").with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(create("测试").param("notes", "字".repeat(2001)).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/submissions").param("operation", "INVALID").with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/submissions").param("operation", "DELETE").param("dishId", "oops").with(csrf())).andExpect(status().isBadRequest());
    }

    @Test void pendingImagesArePrivateAndPublishedImagesAreReadable() throws Exception {
        String name = "带图-" + System.nanoTime();
        long id = submit(create(name).file(new MockMultipartFile("image", "dish.png", "image/png", pngImage()))
                .param("imageAction", "REPLACE"));
        JsonNode all = json(mvc.perform(get("/api/chef/submissions?status=ALL").with(chef())).andReturn());
        String imageUrl = null;
        for (JsonNode n : all) if (n.path("id").asLong() == id) imageUrl = n.path("after").path("imageUrl").asText();
        assertThat(imageUrl).startsWith("/media/");
        mvc.perform(get(imageUrl)).andExpect(status().isNotFound());
        mvc.perform(get(imageUrl).with(user("admin").roles("ADMIN"))).andExpect(status().isNotFound());
        mvc.perform(get(imageUrl).with(chef())).andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG));
        approve(id, 200);
        mvc.perform(get(imageUrl)).andExpect(status().isOk()).andExpect(header().string("X-Content-Type-Options", "nosniff"));
        JsonNode dish = findDish(name);
        long removed = submit(change("UPDATE", dish).param("name", name).param("imageAction", "REMOVE"));
        approve(removed, 200);
        mvc.perform(get(imageUrl)).andExpect(status().isNotFound());
    }

    @Test void rejectsFakeAndOversizedImages() throws Exception {
        mvc.perform(create("假图").file(new MockMultipartFile("image", "fake.png", "image/png", "<script>alert(1)</script>".getBytes()))
                .param("imageAction", "REPLACE").with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(create("大图").file(new MockMultipartFile("image", "large.png", "image/png", new byte[5 * 1024 * 1024 + 1]))
                .param("imageAction", "REPLACE").with(csrf())).andExpect(status().isPayloadTooLarge());
        mvc.perform(create("损坏图片").file(new MockMultipartFile("image", "broken.png", "image/png",
                        new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}))
                .param("imageAction", "REPLACE").with(csrf())).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @MethodSource("mobilePngUploads")
    void recognizesPngBytesRegardlessOfMobileFilenameOrMime(String filename, String contentType) throws Exception {
        long id = submit(create("手机截图-" + System.nanoTime())
                .file(new MockMultipartFile("image", filename, contentType, pngImage())).param("imageAction", "REPLACE"));
        String imageUrl = findSubmission(id).path("after").path("imageUrl").asText();
        mvc.perform(get(imageUrl).with(chef())).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
        mvc.perform(get(imageUrl)).andExpect(status().isNotFound());
    }

    static Stream<Arguments> mobilePngUploads() {
        return Stream.of(
                Arguments.of("Screenshot.PNG", ""),
                Arguments.of("Screenshot", null),
                Arguments.of("Screenshot.png", "image/x-png"),
                Arguments.of("Screenshot.bin", "application/octet-stream"),
                Arguments.of("Screenshot.jpeg", "image/jpeg"));
    }

    @ParameterizedTest
    @MethodSource("transparentPngs")
    void transparentPngSurvivesGuestReviewAndPublication(String format, BufferedImage source) throws Exception {
        byte[] uploaded = encodedImage(source, "png");
        String name = format + "透明访客图-" + System.nanoTime();
        long id = submit(create(name).file(new MockMultipartFile("image", "dish.png", "image/png", uploaded))
                .param("imageAction", "REPLACE"));
        String imageUrl = findSubmission(id).path("after").path("imageUrl").asText();
        assertThat(findDish(name)).isNull();
        mvc.perform(get(imageUrl)).andExpect(status().isNotFound());
        byte[] pending = mvc.perform(get(imageUrl).with(chef())).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG)).andReturn().getResponse().getContentAsByteArray();
        assertTransparentPixels(pending, source, uploaded);
        approve(id, 200);
        assertThat(findDish(name).path("imageUrl").asText()).isEqualTo(imageUrl);
        byte[] published = mvc.perform(get(imageUrl)).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG)).andReturn().getResponse().getContentAsByteArray();
        assertThat(published).isEqualTo(pending);
        assertTransparentPixels(published, source, uploaded);
    }

    @ParameterizedTest
    @MethodSource("transparentPngs")
    void transparentPngSurvivesDirectChefPublication(String format, BufferedImage source) throws Exception {
        byte[] uploaded = encodedImage(source, "png");
        String name = format + "透明大厨图-" + System.nanoTime();
        mvc.perform(multipart("/api/chef/dishes")
                .file(new MockMultipartFile("image", "dish.png", "image/png", uploaded))
                .param("operation", "CREATE").param("name", name).param("imageAction", "REPLACE").with(chef()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        byte[] published = mvc.perform(get(findDish(name).path("imageUrl").asText())).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG)).andReturn().getResponse().getContentAsByteArray();
        assertTransparentPixels(published, source, uploaded);
    }

    @Test void jpegStillPublishesAsOpaqueJpeg() throws Exception {
        BufferedImage source = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        source.setRGB(1, 1, 0xffc66b35);
        String name = "JPEG兼容-" + System.nanoTime();
        mvc.perform(multipart("/api/chef/dishes")
                .file(new MockMultipartFile("image", "dish.jpg", "image/jpeg", encodedImage(source, "jpeg")))
                .param("operation", "CREATE").param("name", name).param("imageAction", "REPLACE").with(chef()).with(csrf()))
                .andExpect(status().isOk());
        byte[] published = mvc.perform(get(findDish(name).path("imageUrl").asText())).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG)).andReturn().getResponse().getContentAsByteArray();
        BufferedImage restored = decodedImage(published);
        assertThat(restored.getColorModel().hasAlpha()).isFalse();
        assertThat(restored.getWidth()).isEqualTo(source.getWidth());
        assertThat(restored.getHeight()).isEqualTo(source.getHeight());
    }

    static Stream<Arguments> transparentPngs() {
        int[] pixels = { 0x00123456, 0x01c76335, 0x40835629, 0x7f3d95c7,
                0x80c76b35, 0xfe7189ab, 0xffe47a29, 0xff123456 };
        BufferedImage rgba = new BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB);
        rgba.setRGB(0, 0, 4, 2, pixels, 0, 4);
        byte[] red = new byte[pixels.length], green = new byte[pixels.length];
        byte[] blue = new byte[pixels.length], alpha = new byte[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            red[i] = (byte) (pixels[i] >>> 16);
            green[i] = (byte) (pixels[i] >>> 8);
            blue[i] = (byte) pixels[i];
            alpha[i] = (byte) (pixels[i] >>> 24);
        }
        BufferedImage indexed = new BufferedImage(4, 2, BufferedImage.TYPE_BYTE_INDEXED,
                new IndexColorModel(8, pixels.length, red, green, blue, alpha));
        for (int i = 0; i < pixels.length; i++) indexed.getRaster().setSample(i % 4, i / 4, 0, i);
        return Stream.of(Arguments.of("RGBA", rgba), Arguments.of("indexed-tRNS", indexed));
    }

    private static byte[] encodedImage(BufferedImage source, String format) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            assertThat(ImageIO.write(source, format, output)).isTrue();
            output.flush();
        }
        // Re-encoding must discard trailing upload data while retaining the actual pixels.
        bytes.write("untrusted trailing upload data".getBytes(StandardCharsets.US_ASCII));
        return bytes.toByteArray();
    }

    private static BufferedImage decodedImage(byte[] bytes) throws Exception {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var reader = ImageIO.getImageReaders(input).next();
            try {
                reader.setInput(input);
                return reader.read(0);
            } finally { reader.dispose(); }
        }
    }

    private static void assertTransparentPixels(byte[] stored, BufferedImage source, byte[] uploaded) throws Exception {
        assertThat(Arrays.equals(stored, uploaded)).isFalse();
        BufferedImage restored = decodedImage(stored);
        assertThat(restored).isNotNull();
        assertThat(restored.getColorModel().hasAlpha()).isTrue();
        assertThat(restored.getWidth()).isEqualTo(source.getWidth());
        assertThat(restored.getHeight()).isEqualTo(source.getHeight());
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                assertThat(restored.getRGB(x, y)).as("ARGB pixel (%s, %s)", x, y).isEqualTo(source.getRGB(x, y));
            }
        }
    }

    @Test void pastedSharingTextAndMobilePngSurviveSubmissionReviewAndPublication() throws Exception {
        String name = "分享截图-" + System.nanoTime();
        String link = "https://v.douyin.com/AbC123/?token=a%2Fb%3D&from=share#step2";
        long id = submit(create(name).file(new MockMultipartFile("image", "截图.PNG", "application/octet-stream", pngImage()))
                .param("videoUrl", "复制打开抖音，看看这道菜（" + link + "）。\n10/27 分享")
                .param("imageAction", "REPLACE"));
        JsonNode pending = findSubmission(id);
        assertThat(pending.path("after").path("videoUrl").asText()).isEqualTo(link);
        assertThat(findDish(name)).isNull();
        approve(id, 200);
        JsonNode published = findDish(name);
        assertThat(published.path("videoUrl").asText()).isEqualTo(link);
        mvc.perform(get(published.path("imageUrl").asText())).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    private static byte[] pngImage() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            ImageIO.write(new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB), "png", output);
            output.flush();
        }
        return bytes.toByteArray();
    }

    private JsonNode findSubmission(long id) throws Exception {
        for (JsonNode submission : json(mvc.perform(get("/api/chef/submissions?status=ALL").with(chef())).andReturn())) {
            if (submission.path("id").asLong() == id) return submission;
        }
        throw new AssertionError("Submission not found: " + id);
    }

    private MockMultipartHttpServletRequestBuilder create(String name) {
        var request = multipart("/api/submissions");
        request.param("operation", "CREATE").param("name", name);
        return request;
    }
    private MockMultipartHttpServletRequestBuilder change(String operation, JsonNode dish) {
        var request = multipart("/api/submissions");
        request.param("operation", operation).param("dishId", dish.path("id").asText())
                .param("expectedVersion", dish.path("version").asText());
        return request;
    }
    private SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor chef() { return user("reviewer").roles("CHEF"); }
    private JsonNode json(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsByteArray()); }
    private long submit(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request.with(csrf())).andExpect(status().is2xxSuccessful()).andReturn();
        return json(result).path("id").asLong();
    }
    private void approve(long id, int statusCode) throws Exception {
        mvc.perform(post("/api/chef/submissions/{id}/approve", id).with(chef()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"已核对\"}"))
                .andExpect(status().is(statusCode));
    }
    private JsonNode findDish(String name) throws Exception {
        for (JsonNode dish : json(mvc.perform(get("/api/menu")).andReturn())) if (name.equals(dish.path("name").asText())) return dish;
        return null;
    }
    private JsonNode published(String name) throws Exception {
        approve(submit(create(name)), 200);
        return findDish(name);
    }
}
