package com.techdesksystem.techdesk.auth.service;

import com.techdesksystem.techdesk.auth.dto.DepartmentCreateRequest;
import com.techdesksystem.techdesk.auth.dto.DepartmentResponse;
import com.techdesksystem.techdesk.auth.dto.DepartmentUpdateRequest;
import com.techdesksystem.techdesk.auth.entity.Department;
import com.techdesksystem.techdesk.auth.exception.AuthException;
import com.techdesksystem.techdesk.auth.repository.DepartmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DepartmentServiceTests {

    private final DepartmentRepository departmentRepository =
            mock(DepartmentRepository.class);

    private final DepartmentService departmentService =
            new DepartmentService(departmentRepository);

    @Test
    void createsDepartmentWithNormalizedCode() {
        given(departmentRepository.existsByCode("engineering"))
                .willReturn(false);
        given(departmentRepository.save(any(Department.class)))
                .willAnswer(invocation -> withPersistentFields(
                        invocation.getArgument(0),
                        10L
                ));

        DepartmentResponse response = departmentService.createDepartment(
                new DepartmentCreateRequest(" Engineering ", "Engineering")
        );

        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.name()).isEqualTo("Engineering");
        assertThat(response.code()).isEqualTo("engineering");
        assertThat(response.active()).isTrue();
    }

    @Test
    void rejectsDuplicateDepartmentCodeOnCreate() {
        given(departmentRepository.existsByCode("finance"))
                .willReturn(true);

        assertThatThrownBy(() -> departmentService.createDepartment(
                new DepartmentCreateRequest("Finance", "FINANCE")
        ))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("finance");
    }

    @Test
    void updatesDepartmentAndProtectsDuplicateCodes() {
        Department department = department(5L, "Finance", "finance", true);
        given(departmentRepository.findById(5L))
                .willReturn(Optional.of(department));
        given(departmentRepository.existsByCodeAndIdNot("people", 5L))
                .willReturn(false);

        DepartmentResponse response = departmentService.updateDepartment(
                5L,
                new DepartmentUpdateRequest(" People Ops ", "People", false)
        );

        assertThat(response.name()).isEqualTo("People Ops");
        assertThat(response.code()).isEqualTo("people");
        assertThat(response.active()).isFalse();
    }

    @Test
    void softDeletesDepartmentByDeactivatingIt() {
        Department department = department(7L, "IT", "it", true);
        given(departmentRepository.findById(7L))
                .willReturn(Optional.of(department));

        departmentService.deactivateDepartment(7L);

        assertThat(department.isActive()).isFalse();
        verify(departmentRepository).findById(7L);
    }

    private Department department(
            Long id,
            String name,
            String code,
            boolean active
    ) {
        Department department = new Department();
        department.setName(name);
        department.setCode(code);
        department.setActive(active);
        return withPersistentFields(department, id);
    }

    private Department withPersistentFields(Department department, Long id) {
        Instant now = Instant.parse("2026-07-07T00:00:00Z");
        ReflectionTestUtils.setField(department, "id", id);
        department.setCreatedAt(now);
        department.setUpdatedAt(now);
        return department;
    }
}
