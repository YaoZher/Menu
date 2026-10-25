package com.example.menu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
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
    "app.admin.password=Test-password-2026!",
    "app.rate-limit.enabled=false"
})
@AutoConfigureMockMvc
class MenuIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test void publicReadsAndPrivateReviewEndpoints() throws Exception {
        mvc.perform(get("/api/menu")).andExpect(status().isOk());
        mvc.perform(get("/api/admin/submissions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/admin.html")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/admin/me").with(user("visitor").roles("USER"))).andExpect(status().isForbidden());
    }

    @Test void csrfRequiredForAnonymousSubmissionsAndReview() throws Exception {
        mvc.perform(multipart("/api/submissions").param("operation", "CREATE").param("name", "豆腐"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/submissions/1/approve").with(admin()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test void realSessionLoginRefreshesCsrfAndLogoutProtectsReview() throws Exception {
        MvcResult csrfResult = mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) csrfResult.getRequest().getSession(false);
        JsonNode token = json(csrfResult);
        mvc.perform(post("/api/auth/login").session(session)
                .header(token.path("headerName").asText(), token.path("token").asText())
                .param("username", "admin").param("password", "Test-password-2026!"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("admin"));
        mvc.perform(get("/api/admin/me").session(session)).andExpect(status().isOk());
        JsonNode refreshed = json(mvc.perform(get("/api/csrf").session(session)).andReturn());
        mvc.perform(post("/api/auth/logout").session(session)
                .header(refreshed.path("headerName").asText(), refreshed.path("token").asText())).andExpect(status().isOk());
        mvc.perform(get("/api/admin/me")).andExpect(status().isUnauthorized());
    }

    @Test void wrongPasswordReturnsJsonWithoutLoggingIn() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf()).param("username", "admin").param("password", "wrong"))
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
        mvc.perform(post("/api/admin/submissions/{id}/reject", id).with(admin()).with(csrf())
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
        JsonNode beforeReview = json(mvc.perform(get("/api/admin/submissions?status=ALL").with(admin())).andReturn());
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
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB), "png", image);
        String name = "带图-" + System.nanoTime();
        long id = submit(create(name).file(new MockMultipartFile("image", "dish.png", "image/png", image.toByteArray()))
                .param("imageAction", "REPLACE"));
        JsonNode all = json(mvc.perform(get("/api/admin/submissions?status=ALL").with(admin())).andReturn());
        String imageUrl = null;
        for (JsonNode n : all) if (n.path("id").asLong() == id) imageUrl = n.path("after").path("imageUrl").asText();
        assertThat(imageUrl).startsWith("/media/");
        mvc.perform(get(imageUrl)).andExpect(status().isNotFound());
        mvc.perform(get(imageUrl).with(admin())).andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG));
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
    private SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor admin() { return user("reviewer").roles("ADMIN"); }
    private JsonNode json(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsByteArray()); }
    private long submit(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request.with(csrf())).andExpect(status().is2xxSuccessful()).andReturn();
        return json(result).path("id").asLong();
    }
    private void approve(long id, int statusCode) throws Exception {
        mvc.perform(post("/api/admin/submissions/{id}/approve", id).with(admin()).with(csrf())
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
