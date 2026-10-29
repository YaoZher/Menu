package com.example.menu.service;

import com.example.menu.domain.*;
import com.example.menu.service.MenuViews.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class MenuService {
    private final DishRepository dishes;
    private final SubmissionRepository submissions;
    private final ImageService images;
    private final ShoppingService shopping;

    public MenuService(DishRepository dishes, SubmissionRepository submissions, ImageService images, ShoppingService shopping) {
        this.dishes = dishes;
        this.submissions = submissions;
        this.images = images;
        this.shopping = shopping;
    }

    @Transactional(readOnly = true) // 只读事务：这个事务预期只读取数据。
    /*
    在 Spring Data JPA + Hibernate 环境下，这可能带来以下优化：
    1.Hibernate 可以采用更适合只读操作的刷新和脏检查策略。
    2.JDBC 连接可能被设置为只读，具体取决于事务管理器与驱动。
    3.可以明确区分查询操作和写入操作。
    */
    public List<DishView> listMenu() {
        return dishes.findAllByOrderByUpdatedAtDescIdDesc() // 从数据库查询所有菜品，按更新时间和 ID 倒序排列。
        .stream()
        .map(DishView::from) // 将 Dish 实体转换为 DishView 响应对象。
        .toList();
    }

    @Transactional
    public SubmissionReceipt submit(SubmissionCommand command) {
        ChangeOperation operation = parseEnum(ChangeOperation.class, command.operation(), "请选择有效的提交操作");
        Long dishId = parseLong(command.dishId(), "菜品编号无效", 1);
        Long expectedVersion = parseLong(command.expectedVersion(), "菜品版本无效", 0);
        DishSnapshot before = null;
        if (operation == ChangeOperation.CREATE) {
            if (dishId != null || expectedVersion != null) throw ApiException.badRequest("新增菜品无需菜品编号或版本");
        } else {
            if (dishId == null || expectedVersion == null) throw ApiException.badRequest("请提供菜品编号和当前版本");
            Dish dish = dishes.findById(dishId).orElseThrow(() -> ApiException.notFound("菜品不存在，请刷新菜单"));
            if (dish.getVersion() != expectedVersion) throw ApiException.conflict("菜品已更新，请刷新菜单后重新提交");
            before = dish.snapshot();
        }
        DishSnapshot after = null;
        if (operation != ChangeOperation.DELETE) {
            String name = clean(command.name(), 80, "菜名");
            if (name == null) throw ApiException.badRequest("请填写菜名");
            if (name.codePoints().anyMatch(Character::isISOControl)) throw ApiException.badRequest("菜名不能包含换行或控制字符");
            String videoUrl = VideoLinks.extract(command.videoUrl());
            String notes = clean(command.notes(), 2000, "备注");
            boolean hasFile = command.image() != null && !command.image().isEmpty();
            String rawAction = command.imageAction();
            if (rawAction == null || rawAction.isBlank()) rawAction = hasFile ? "REPLACE" : "KEEP";
            ImageAction action = parseEnum(ImageAction.class, rawAction, "图片操作无效");
            if (hasFile && action != ImageAction.REPLACE) throw ApiException.badRequest("图片上传操作不一致，请重试");
            UUID imageId = switch (action) {
                case KEEP -> before == null ? null : before.getImageId();
                case REMOVE -> null;
                case REPLACE -> images.save(command.image());
            };
            after = new DishSnapshot(name, imageId, videoUrl, notes);
        } else if (command.image() != null && !command.image().isEmpty()) {
            throw ApiException.badRequest("删除申请无需上传图片");
        }
        Submission submission = new Submission(operation, dishId, expectedVersion, before, after, Instant.now());
        return SubmissionReceipt.from(submissions.save(submission));
    }

    @Transactional
    public SubmissionView saveAsChef(SubmissionCommand command, String chef) {
        SubmissionReceipt receipt = submit(command);
        return approve(receipt.id(), null, chef);
    }

    @Transactional(readOnly = true)
    public List<SubmissionView> listSubmissions(String status) {
        if (status == null || status.isBlank()) status = "PENDING";
        List<Submission> found = "ALL".equals(status)
                ? submissions.findAllByOrderByCreatedAtDescIdDesc()
                : submissions.findByStatusOrderByCreatedAtDescIdDesc(parseEnum(SubmissionStatus.class, status, "审核状态无效"));
        return found.stream().map(SubmissionView::from).toList();
    }

    @Transactional
    public SubmissionView approve(Long id, String note, String reviewer) {
        String cleanNote = clean(note, 1000, "审核备注");
        Submission submission = pendingSubmission(id);
        Instant now = Instant.now();
        switch (submission.getOperation()) {
            case CREATE -> {
                Dish dish = dishes.saveAndFlush(new Dish(submission.getAfterSnapshot(), now));
                submission.setCreatedDishId(dish.getId());
            }
            case UPDATE -> {
                Dish dish = currentDish(submission);
                dish.apply(submission.getAfterSnapshot(), now);
                dishes.flush();
                shopping.synchronizeDish(dish);
            }
            case DELETE -> {
                Dish dish = currentDish(submission);
                shopping.removeDish(dish);
                dishes.delete(dish);
                dishes.flush();
            }
        }
        submission.review(SubmissionStatus.APPROVED, reviewer, cleanNote, now);
        submissions.flush();
        return SubmissionView.from(submission);
    }

    @Transactional
    public SubmissionView reject(Long id, String note, String reviewer) {
        String cleanNote = clean(note, 1000, "审核备注");
        Submission submission = pendingSubmission(id);
        submission.review(SubmissionStatus.REJECTED, reviewer, cleanNote, Instant.now());
        submissions.flush();
        return SubmissionView.from(submission);
    }

    private Submission pendingSubmission(Long id) {
        Submission submission = submissions.findForUpdate(id).orElseThrow(() -> ApiException.notFound("申请不存在"));
        if (submission.getStatus() != SubmissionStatus.PENDING) throw ApiException.conflict("此申请已经审核，请刷新列表");
        return submission;
    }

    private Dish currentDish(Submission submission) {
        Dish dish = dishes.findForUpdate(submission.getDishId())
                .orElseThrow(() -> ApiException.conflict("原菜品已被删除，无法批准此申请，请驳回并重新提交"));
        if (dish.getVersion() != submission.getBaseVersion()) {
            throw ApiException.conflict("原菜品已更新，无法批准旧版本申请，请驳回并重新提交");
        }
        return dish;
    }

    private static String clean(String value, int limit, String label) {
        if (value == null) return null;
        String cleaned = value.strip();
        if (cleaned.length() > limit) throw ApiException.badRequest(label + "不能超过 " + limit + " 个字符");
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static Long parseLong(String value, String message, long minimum) {
        if (value == null || value.isBlank()) return null;
        try {
            long parsed = Long.parseLong(value);
            if (parsed < minimum) throw ApiException.badRequest(message);
            return parsed;
        } catch (NumberFormatException exception) { throw ApiException.badRequest(message); }
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> type, String value, String message) {
        try { return Enum.valueOf(type, value == null ? "" : value); }
        catch (IllegalArgumentException exception) { throw ApiException.badRequest(message); }
    }

    private enum ImageAction { KEEP, REMOVE, REPLACE }
}
