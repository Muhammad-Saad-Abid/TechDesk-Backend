package com.techdesksystem.techdesk.auth.controller;

import com.techdesksystem.techdesk.auth.dto.DepartmentCreateRequest;
import com.techdesksystem.techdesk.auth.dto.DepartmentResponse;
import com.techdesksystem.techdesk.auth.dto.DepartmentUpdateRequest;
import com.techdesksystem.techdesk.auth.security.RequiresPermission;
import com.techdesksystem.techdesk.auth.service.DepartmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/departments")
public class DepartmentController {

    private final DepartmentService departmentService;

    public DepartmentController(DepartmentService departmentService) {
        this.departmentService = departmentService;
    }

    @PostMapping
    @RequiresPermission("departments:create")
    public ResponseEntity<DepartmentResponse> create(
            @Valid @RequestBody DepartmentCreateRequest request
    ) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(departmentService.createDepartment(request));
    }

    @GetMapping
    @RequiresPermission("departments:read")
    public Page<DepartmentResponse> list(
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false, name = "q") String search,
            Pageable pageable
    ) {
        return departmentService.listDepartments(active, search, pageable);
    }

    @GetMapping("/{departmentId}")
    @RequiresPermission("departments:read")
    public DepartmentResponse get(
            @Positive @PathVariable Long departmentId
    ) {
        return departmentService.getDepartment(departmentId);
    }

    @PatchMapping("/{departmentId}")
    @RequiresPermission("departments:update")
    public DepartmentResponse update(
            @Positive @PathVariable Long departmentId,
            @Valid @RequestBody DepartmentUpdateRequest request
    ) {
        return departmentService.updateDepartment(departmentId, request);
    }

    @DeleteMapping("/{departmentId}")
    @RequiresPermission("departments:delete")
    public ResponseEntity<Void> delete(
            @Positive @PathVariable Long departmentId
    ) {
        departmentService.deactivateDepartment(departmentId);
        return ResponseEntity.noContent().build();
    }
}
