package com.coruja.repositories;

import com.coruja.entities.KmRodovia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KmRodoviaRepository extends JpaRepository<KmRodovia, Long> {
    List<KmRodovia> findByRodoviaId(Long rodoviaId);
}
