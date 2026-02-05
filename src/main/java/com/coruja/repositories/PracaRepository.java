package com.coruja.repositories;

import com.coruja.entities.Praca;
import com.coruja.entities.Rodovia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PracaRepository extends JpaRepository<Praca, Long> {
    boolean existsByNome(String nome);
}
