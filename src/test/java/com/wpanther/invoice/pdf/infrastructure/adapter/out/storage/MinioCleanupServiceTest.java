package com.wpanther.invoice.pdf.infrastructure.adapter.out.storage;

import com.wpanther.invoice.pdf.infrastructure.adapter.out.persistence.JpaInvoicePdfDocumentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@ExtendWith(MockitoExtension.class)
@DisplayName("MinioCleanupService Unit Tests")
class MinioCleanupServiceTest {

    @Mock
    private MinioStorageAdapter minioStorage;

    @Mock
    private JpaInvoicePdfDocumentRepository repository;

    // Note: Using reflection to instantiate because Lombok @RequiredArgsConstructor
    // is scope=provided, not available during test compilation
    private MinioCleanupService getCleanupService() {
        try {
            return MinioCleanupService.class
                    .getDeclaredConstructor(MinioStorageAdapter.class, JpaInvoicePdfDocumentRepository.class)
                    .newInstance(minioStorage, repository);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate MinioCleanupService", e);
        }
    }

    @Test
    @DisplayName("cleanupOrphanedPdfs() deletes objects not in database")
    void testCleanupOrphanedPdfs() {
        // Given
        List<String> minioKeys = List.of(
                "2024/01/15/invoice-INV-001-abc123.pdf",
                "2024/01/15/invoice-INV-002-def456.pdf",
                "2024/01/15/invoice-INV-003-orphan.pdf"
        );
        Set<String> databaseKeys = Set.of(
                "2024/01/15/invoice-INV-001-abc123.pdf",
                "2024/01/15/invoice-INV-002-def456.pdf"
        );

        when(minioStorage.listAllPdfs()).thenReturn(minioKeys);
        when(repository.findAllDocumentPaths()).thenReturn(databaseKeys);

        // When
        getCleanupService().cleanupOrphanedPdfs();

        // Then
        verify(minioStorage).listAllPdfs();
        verify(repository).findAllDocumentPaths();
        verify(minioStorage).deleteWithoutCircuitBreaker("2024/01/15/invoice-INV-003-orphan.pdf");
        verify(minioStorage, never()).deleteWithoutCircuitBreaker("2024/01/15/invoice-INV-001-abc123.pdf");
        verify(minioStorage, never()).deleteWithoutCircuitBreaker("2024/01/15/invoice-INV-002-def456.pdf");
    }

    @Test
    @DisplayName("cleanupOrphanedPdfs() does nothing when no orphaned objects exist")
    void testCleanupOrphanedPdfs_NoOrphans() {
        // Given
        List<String> minioKeys = List.of(
                "2024/01/15/invoice-INV-001-abc123.pdf",
                "2024/01/15/invoice-INV-002-def456.pdf"
        );
        Set<String> databaseKeys = Set.of(
                "2024/01/15/invoice-INV-001-abc123.pdf",
                "2024/01/15/invoice-INV-002-def456.pdf"
        );

        when(minioStorage.listAllPdfs()).thenReturn(minioKeys);
        when(repository.findAllDocumentPaths()).thenReturn(databaseKeys);

        // When
        getCleanupService().cleanupOrphanedPdfs();

        // Then
        verify(minioStorage).listAllPdfs();
        verify(repository).findAllDocumentPaths();
        verify(minioStorage, never()).deleteWithoutCircuitBreaker(anyString());
    }

    @Test
    @DisplayName("cleanupOrphanedPdfs() handles empty MinIO bucket")
    void testCleanupOrphanedPdfs_EmptyBucket() {
        // Given
        List<String> minioKeys = List.of();
        Set<String> databaseKeys = Set.of(
                "2024/01/15/invoice-INV-001-abc123.pdf"
        );

        when(minioStorage.listAllPdfs()).thenReturn(minioKeys);
        when(repository.findAllDocumentPaths()).thenReturn(databaseKeys);

        // When
        getCleanupService().cleanupOrphanedPdfs();

        // Then
        verify(minioStorage).listAllPdfs();
        verify(repository).findAllDocumentPaths();
        verify(minioStorage, never()).deleteWithoutCircuitBreaker(anyString());
    }

    @Test
    @DisplayName("cleanupOrphanedPdfs() handles all orphaned objects")
    void testCleanupOrphanedPdfs_AllOrphans() {
        // Given
        List<String> minioKeys = List.of(
                "2024/01/15/invoice-INV-001-abc123.pdf",
                "2024/01/15/invoice-INV-002-def456.pdf"
        );
        Set<String> databaseKeys = Set.of();

        when(minioStorage.listAllPdfs()).thenReturn(minioKeys);
        when(repository.findAllDocumentPaths()).thenReturn(databaseKeys);

        // When
        getCleanupService().cleanupOrphanedPdfs();

        // Then
        verify(minioStorage).listAllPdfs();
        verify(repository).findAllDocumentPaths();
        verify(minioStorage).deleteWithoutCircuitBreaker("2024/01/15/invoice-INV-001-abc123.pdf");
        verify(minioStorage).deleteWithoutCircuitBreaker("2024/01/15/invoice-INV-002-def456.pdf");
    }

    @Test
    @DisplayName("cleanupOrphanedPdfs() handles MinIO listing errors gracefully")
    void testCleanupOrphanedPdfs_ListingError() {
        // Given
        when(minioStorage.listAllPdfs()).thenThrow(new RuntimeException("MinIO connection error"));

        // When/Then - should not propagate exception
        assertDoesNotThrow(() -> getCleanupService().cleanupOrphanedPdfs());

        verify(minioStorage).listAllPdfs();
        verify(repository, never()).findAllDocumentPaths();
    }

    @Test
    @DisplayName("cleanupOrphanedPdfs() handles database query errors gracefully")
    void testCleanupOrphanedPdfs_DatabaseError() {
        // Given
        List<String> minioKeys = List.of("2024/01/15/invoice-INV-001-abc123.pdf");
        when(minioStorage.listAllPdfs()).thenReturn(minioKeys);
        when(repository.findAllDocumentPaths()).thenThrow(new RuntimeException("Database connection error"));

        // When/Then - should not propagate exception
        assertDoesNotThrow(() -> getCleanupService().cleanupOrphanedPdfs());

        verify(minioStorage).listAllPdfs();
        verify(repository).findAllDocumentPaths();
    }
}
