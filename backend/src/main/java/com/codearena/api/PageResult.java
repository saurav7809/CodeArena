package com.codearena.api;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record PageResult<T>(List<T> items, int page, int size, long total) {
    public static int offset(int page, int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid page and a page size from 1 to 50.");
        }
        return page * size;
    }
}
