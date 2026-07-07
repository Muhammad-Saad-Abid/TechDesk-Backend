package com.techdesksystem.techdesk.auth.service;

import com.techdesksystem.techdesk.auth.dto.DepartmentCreateRequest;
import com.techdesksystem.techdesk.auth.dto.DepartmentResponse;
import com.techdesksystem.techdesk.auth.dto.DepartmentUpdateRequest;
import com.techdesksystem.techdesk.auth.entity.Department;
import com.techdesksystem.techdesk.auth.exception.AuthException;
import com.techdesksystem.techdesk.auth.repository.DepartmentRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
public class DepartmentService {

    private final DepartmentRepository departmentRepository;

    public DepartmentService(DepartmentRepository departmentRepository) {
        this.departmentRepository = departmentRepository;
    }

    /**
     * Creates a tenant-local department after normalizing its code and
     * guarding the natural unique key before the database constraint fires.
     */
    @Transactional
    public DepartmentResponse createDepartment(
            DepartmentCreateRequest request
    ) {
        String code = normalizeRequiredCode(request.code());
        if (departmentRepository.existsByCode(code)) {
            throw duplicateCode(code);
        }

        Department department = new Department();
        department.setName(normalizeRequiredName(request.name()));
        department.setCode(code);
        department.setActive(true);

        return toResponse(departmentRepository.save(department));
    }

    /**
     * Lists departments using repository-level criteria so tenant-local data
     * stays behind the JPA repository boundary.
     */
    @Transactional(readOnly = true)
    public Page<DepartmentResponse> listDepartments(
            Boolean active,
            String search,
            Pageable pageable
    ) {
        return departmentRepository.findAll(
                filters(active, search),
                pageable
        ).map(this::toResponse);
    }

    /**
     * Returns a single tenant-local department or a consistent API error.
     */
    @Transactional(readOnly = true)
    public DepartmentResponse getDepartment(Long departmentId) {
        return toResponse(findDepartment(departmentId));
    }

    /**
     * Applies partial updates while protecting immutable tenant-wide
     * uniqueness for department codes.
     */
    @Transactional
    public DepartmentResponse updateDepartment(
            Long departmentId,
            DepartmentUpdateRequest request
    ) {
        if (!hasAnyUpdate(request)) {
            throw AuthException.badRequest(
                    "NO_DEPARTMENT_UPDATES",
                    "At least one department field must be provided."
            );
        }

        Department department = findDepartment(departmentId);

        if (request.name() != null) {
            department.setName(normalizeRequiredName(request.name()));
        }

        if (request.code() != null) {
            String code = normalizeRequiredCode(request.code());
            if (!code.equals(department.getCode())
                    && departmentRepository.existsByCodeAndIdNot(
                    code,
                    departmentId
            )) {
                throw duplicateCode(code);
            }
            department.setCode(code);
        }

        if (request.active() != null) {
            department.setActive(request.active());
        }

        return toResponse(department);
    }

    /**
     * Soft-deletes a department by deactivating it, preserving historical user
     * references and avoiding destructive organizational data loss.
     */
    @Transactional
    public void deactivateDepartment(Long departmentId) {
        Department department = findDepartment(departmentId);
        department.setActive(false);
    }

    private Department findDepartment(Long departmentId) {
        return departmentRepository.findById(departmentId)
                .orElseThrow(() -> AuthException.notFound(
                        "DEPARTMENT_NOT_FOUND",
                        "Department was not found."
                ));
    }

    private Specification<Department> filters(
            Boolean active,
            String search
    ) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (active != null) {
                predicates.add(criteriaBuilder.equal(
                        root.get("active"),
                        active
                ));
            }

            if (StringUtils.hasText(search)) {
                String pattern = "%"
                        + search.trim().toLowerCase()
                        + "%";
                predicates.add(criteriaBuilder.or(
                        criteriaBuilder.like(
                                criteriaBuilder.lower(root.get("name")),
                                pattern
                        ),
                        criteriaBuilder.like(
                                criteriaBuilder.lower(root.get("code")),
                                pattern
                        )
                ));
            }

            return criteriaBuilder.and(
                    predicates.toArray(Predicate[]::new)
            );
        };
    }

    private boolean hasAnyUpdate(DepartmentUpdateRequest request) {
        return request.name() != null
                || request.code() != null
                || request.active() != null;
    }

    private String normalizeRequiredName(String name) {
        if (!StringUtils.hasText(name)) {
            throw AuthException.badRequest(
                    "INVALID_DEPARTMENT_NAME",
                    "Department name is required."
            );
        }

        return name.trim();
    }

    private String normalizeRequiredCode(String code) {
        if (!StringUtils.hasText(code)) {
            throw AuthException.badRequest(
                    "INVALID_DEPARTMENT_CODE",
                    "Department code is required."
            );
        }

        return code.trim().toLowerCase();
    }

    private AuthException duplicateCode(String code) {
        return AuthException.conflict(
                "DEPARTMENT_CODE_ALREADY_EXISTS",
                "Department code already exists: " + code
        );
    }

    private DepartmentResponse toResponse(Department department) {
        return new DepartmentResponse(
                department.getId(),
                department.getName(),
                department.getCode(),
                department.isActive(),
                department.getCreatedAt(),
                department.getUpdatedAt()
        );
    }
}
