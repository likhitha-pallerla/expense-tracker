package com.expensetracker.api.imports;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.expensetracker.api.security.CurrentUser;

import jakarta.validation.Valid;

/**
 * CSV statement import.
 *
 * <p>Preview first, commit second. The preview writes nothing, so a wrong
 * column guess is caught by the user rather than by their bank balance.
 */
@RestController
@RequestMapping("/api/imports")
public class ImportController {

    private final ImportService imports;

    public ImportController(ImportService imports) {
        this.imports = imports;
    }

    @PostMapping("/preview")
    public ImportDtos.Preview preview(@Valid @RequestBody ImportDtos.PreviewRequest request) {
        return imports.preview(CurrentUser.id(), request);
    }

    @PostMapping
    public ImportDtos.Result commit(@Valid @RequestBody ImportDtos.CommitRequest request) {
        return imports.commit(CurrentUser.id(), request);
    }
}
