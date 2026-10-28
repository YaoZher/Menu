package com.example.menu.web;

import com.example.menu.service.MenuService;
import com.example.menu.service.MenuViews.SubmissionView;
import com.example.menu.service.SubmissionCommand;
import java.security.Principal;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/chef/dishes")
public class ChefMenuController {
    private final MenuService menu;

    public ChefMenuController(MenuService menu) { this.menu = menu; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SubmissionView save(
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String dishId,
            @RequestParam(required = false) String expectedVersion,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String videoUrl,
            @RequestParam(required = false) String notes,
            @RequestParam(required = false) String imageAction,
            @RequestPart(required = false) MultipartFile image,
            Principal principal) {
        return menu.saveAsChef(new SubmissionCommand(operation, dishId, expectedVersion, name,
                videoUrl, notes, imageAction, image), principal.getName());
    }
}
