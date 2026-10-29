package com.example.menu.web;

import com.example.menu.service.MenuService;
import com.example.menu.service.MenuViews.DishView;
import com.example.menu.service.MenuViews.SubmissionReceipt;
import com.example.menu.service.SubmissionCommand;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

@RestController
@RequestMapping("/api")
public class MenuController {
    private final MenuService menu;

    public MenuController(MenuService menu) { this.menu = menu; }

    @GetMapping("/menu")
    public List<DishView> menu() { return menu.listMenu(); }

    @PostMapping(value = "/submissions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SubmissionReceipt submit(
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String dishId,
            @RequestParam(required = false) String expectedVersion,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String videoUrl,
            @RequestParam(required = false) String notes,
            @RequestParam(required = false) String imageAction,
            @RequestParam(required = false) String tagIds,
            @RequestPart(required = false) MultipartFile image) {
        return menu.submit(new SubmissionCommand(operation, dishId, expectedVersion, name,
                videoUrl, notes, imageAction, image, tagIds));
    }
}
