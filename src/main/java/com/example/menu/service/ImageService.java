package com.example.menu.service;

import com.example.menu.domain.DishRepository;
import com.example.menu.domain.StoredImage;
import com.example.menu.domain.StoredImageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

@Service
public class ImageService {
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private final StoredImageRepository images;
    private final DishRepository dishes;

    public ImageService(StoredImageRepository images, DishRepository dishes) {
        this.images = images;
        this.dishes = dishes;
    }

    @Transactional
    public UUID save(MultipartFile file) {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("请选择需要上传的图片");
        if (file.getSize() > MAX_BYTES) throw ApiException.tooLarge("图片大小不能超过 5 MB");
        ImageContent sanitized = sanitize(file);
        UUID id = UUID.randomUUID();
        images.save(new StoredImage(id, sanitized.contentType(), sanitized.bytes()));
        return id;
    }

    private ImageContent sanitize(MultipartFile file) {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(file.getBytes()))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw ApiException.badRequest("请选择有效的 PNG 或 JPEG 图片");
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("png") && !format.equals("jpeg") && !format.equals("jpg")) {
                    throw ApiException.badRequest("仅支持 PNG 或 JPEG 图片");
                }
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 4096 || height > 4096 || (long) width * height > 16_000_000) {
                    throw ApiException.badRequest("图片宽高不能超过 4096 像素，且总像素不能超过 1600 万");
                }
                BufferedImage source = reader.read(0);
                if (source == null) throw ApiException.badRequest("图片无法读取，请重新选择");
                boolean png = format.equals("png");
                BufferedImage clean = new BufferedImage(width, height,
                        png ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = clean.createGraphics();
                try { graphics.drawImage(source, 0, 0, null); }
                finally { graphics.dispose(); source.flush(); }
                try {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    ImageWriter writer = ImageIO.getImageWritersByFormatName(png ? "png" : "jpeg").next();
                    try (var output = new MemoryCacheImageOutputStream(buffer)) {
                        writer.setOutput(output);
                        writer.write(null, new IIOImage(clean, null, null), writer.getDefaultWriteParam());
                        output.flush();
                    } finally { writer.dispose(); }
                    if (buffer.size() > MAX_BYTES) throw ApiException.tooLarge("图片处理后超过 5 MB，请压缩后重试");
                    return new ImageContent(buffer.toByteArray(), png ? "image/png" : "image/jpeg");
                } finally { clean.flush(); }
            } finally { reader.dispose(); }
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw ApiException.badRequest("图片无法读取，请上传有效的 PNG 或 JPEG 图片");
        }
    }

    @Transactional(readOnly = true)
    public ImageContent read(UUID id, boolean chef) {
        if (!chef && !dishes.existsByImageId(id)) throw ApiException.notFound("图片不存在");
        StoredImage image = images.findById(id).orElseThrow(() -> ApiException.notFound("图片不存在"));
        return new ImageContent(image.getContent(), image.getContentType());
    }

    public record ImageContent(byte[] bytes, String contentType) { }
}
