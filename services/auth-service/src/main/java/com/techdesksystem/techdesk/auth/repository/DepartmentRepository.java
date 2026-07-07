package com.techdesksystem.techdesk.auth.repository;

import com.techdesksystem.techdesk.auth.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DepartmentRepository
        extends JpaRepository<Department, Long>,
        JpaSpecificationExecutor<Department> {

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Long id);
}
