package com.example.menu.web;

import com.example.menu.service.ApiException;
import com.example.menu.service.ImageService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
public class MediaController {
    private final ImageService images;

    public MediaController(ImageService images) { this.images = images; }

    @GetMapping("/media/{id}")
    public ResponseEntity<byte[]> image(@PathVariable String id, Authentication authentication) {
        UUID imageId;
        try {
            imageId = UUID.fromString(id);
            if (!imageId.toString().equalsIgnoreCase(id)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) { throw ApiException.notFound("图片不存在"); }
        boolean chef = authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(a -> "ROLE_CHEF".equals(a.getAuthority()));
        ImageService.ImageContent image = images.read(imageId, chef);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .contentLength(image.bytes().length)
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .body(image.bytes());
    }
}
