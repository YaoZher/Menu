package com.example.menu.web;

import com.example.menu.service.ApiException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class ApiExceptionAdvice {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> api(ApiException exception) {
        return error(exception.getStatus(), exception.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorBody> uploadTooLarge(MaxUploadSizeExceededException exception) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "图片大小不能超过 5 MB");
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class, HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class, MultipartException.class})
    public ResponseEntity<ErrorBody> invalidInput(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "提交内容格式不正确，请检查后重试");
    }

    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ErrorBody> concurrentUpdate(ConcurrencyFailureException exception) {
        return error(HttpStatus.CONFLICT, "数据刚刚发生变化，请刷新后重试");
    }

    private ResponseEntity<ErrorBody> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ErrorBody(message));
    }

    public record ErrorBody(String message) { }
}
