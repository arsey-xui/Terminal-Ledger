package com.personal.accounting.controller;

import com.personal.accounting.dto.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/photos")
public class PhotoController {

    @Value("${accounting.upload-dir:./uploads}")
    private String uploadDir;

    /** 上传账单照片，返回相对路径（后续通过静态映射或网关访问）。 */
    @PostMapping
    public ApiResponse<Map<String, String>> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            return ApiResponse.fail("文件为空");
        }
        Path dir = Paths.get(uploadDir);
        Files.createDirectories(dir);
        String ext = "";
        String name = file.getOriginalFilename();
        if (name != null && name.contains(".")) {
            ext = name.substring(name.lastIndexOf('.'));
        }
        String filename = UUID.randomUUID() + ext;
        Path dest = dir.resolve(filename).normalize();
        file.transferTo(dest.toFile());
        return ApiResponse.ok(Map.of("path", dest.toString(), "url", "/api/photos/" + filename));
    }
}
