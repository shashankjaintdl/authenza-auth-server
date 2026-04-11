package com.authenza.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.data.domain.Page;

import java.time.LocalDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private LocalDateTime timestamp;
    private int status;
    private boolean success;
    private String message;
    private T data;

    // Only populated when sending paginated responses
    private PaginationMeta pagination;

    // Private constructor to force use of factory methods
    private ApiResponse(int status, boolean success, String message, T data, PaginationMeta pagination) {
        this.timestamp = LocalDateTime.now();
        this.status = status;
        this.success = success;
        this.message = message;
        this.data = data;
        this.pagination = pagination;
    }

    // Default constructor for Jackson JSON Deserialization
    public ApiResponse() {
        this.timestamp = LocalDateTime.now();
    }

    /**
     * Custom Pagination Metadata Inner Class
     */
    public static class PaginationMeta {
        private int currentPage;
        private int pageSize;
        private int totalPages;
        private long totalElements;
        private boolean hasNext;
        private boolean hasPrevious;

        public PaginationMeta() {}

        public PaginationMeta(int currentPage, int pageSize, int totalPages, long totalElements, boolean hasNext, boolean hasPrevious) {
            this.currentPage = currentPage;
            this.pageSize = pageSize;
            this.totalPages = totalPages;
            this.totalElements = totalElements;
            this.hasNext = hasNext;
            this.hasPrevious = hasPrevious;
        }

        // Getters and Setters for PaginationMeta
        public int getCurrentPage() { return currentPage; }
        public void setCurrentPage(int currentPage) { this.currentPage = currentPage; }

        public int getPageSize() { return pageSize; }
        public void setPageSize(int pageSize) { this.pageSize = pageSize; }

        public int getTotalPages() { return totalPages; }
        public void setTotalPages(int totalPages) { this.totalPages = totalPages; }

        public long getTotalElements() { return totalElements; }
        public void setTotalElements(long totalElements) { this.totalElements = totalElements; }

        public boolean isHasNext() { return hasNext; }
        public void setHasNext(boolean hasNext) { this.hasNext = hasNext; }

        public boolean isHasPrevious() { return hasPrevious; }
        public void setHasPrevious(boolean hasPrevious) { this.hasPrevious = hasPrevious; }
    }

    // ==========================================
    // Easy Factory Methods for cleaner Controllers
    // ==========================================

    /**
     * Standard Success Response (No Pagination)
     */
    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(200, true, message, data, null);
    }

    /**
     * Standard Success Created Response (No Pagination)
     */
    public static <T> ApiResponse<T> created(T data, String message) {
        return new ApiResponse<>(201, true, message, data, null);
    }

    public static <T> ApiResponse<T> created(String message) {
        return new ApiResponse<>(201, true, message, null, null);
    }

    /**
     * Standard Success Response with Default Message
     */
    public static <T> ApiResponse<T> success(T data) {
        return success(data, "Operation successful");
    }

    /**
     * Raw Paginated Setup
     */
    public static <T> ApiResponse<T> paginatedSuccess(T data, PaginationMeta pagination, String message) {
        return new ApiResponse<>(200, true, message, data, pagination);
    }

    /**
     * Helper to auto-convert a Spring Data Page<T> into your Custom ApiResponse
     */
    public static <T> ApiResponse<List<T>> paginated(Page<T> page, String message) {
        PaginationMeta meta = new PaginationMeta(
                page.getNumber(),
                page.getSize(),
                page.getTotalPages(),
                page.getTotalElements(),
                page.hasNext(),
                page.hasPrevious()
        );

        return new ApiResponse<>(200, true, message, page.getContent(), meta);
    }

    /**
     * Standard Error Response (Custom Status)
     */
    public static <T> ApiResponse<T> error(int status, String message) {
        return new ApiResponse<>(status, false, message, null, null);
    }

    /**
     * 204 No Content Response (Useful for DELETE operations)
     */
    public static <T> ApiResponse<T> noContent(String message) {
        return new ApiResponse<>(204, true, message, null, null);
    }

    /**
     * 202 Accepted (Useful for asynchronous background jobs like Bulk User Import)
     */
    public static <T> ApiResponse<T> accepted(String message) {
        return new ApiResponse<>(202, true, message, null, null);
    }

    /**
     * 400 Bad Request Helper
     */
    public static <T> ApiResponse<T> badRequest(String message) {
        return new ApiResponse<>(400, false, message, null, null);
    }

    /**
     * 405 Method Not Allowed (e.g., sending a POST to a GET endpoint)
     */
    public static <T> ApiResponse<T> methodNotAllowed(String message) {
        return new ApiResponse<>(405, false, message, null, null);
    }

    /**
     * 429 Too Many Requests (Critical for Auth Rate Limiting!)
     */
    public static <T> ApiResponse<T> tooManyRequests(String message) {
        return new ApiResponse<>(429, false, message, null, null);
    }

    /**
     * 409 Conflict Helper (Resource already exists or state conflict)
     */
    public static <T> ApiResponse<T> conflict(String message) {
        return new ApiResponse<>(409, false, message, null, null);
    }

    /**
     * 410 Gone (Perfect for expired Verification Tokens or old API versions)
     */
    public static <T> ApiResponse<T> gone(String message) {
        return new ApiResponse<>(410, false, message, null, null);
    }

    /**
     * 413 Payload Too Large (e.g., uploading a Tenant Logo that exceeds max file size)
     */
    public static <T> ApiResponse<T> payloadTooLarge(String message) {
        return new ApiResponse<>(413, false, message, null, null);
    }

    /**
     * 422 Unprocessable Entity Helper (Form Validation Failures)
     */
    public static <T> ApiResponse<T> unprocessableEntity(String message, T validationData) {
        return new ApiResponse<>(422, false, message, validationData, null);
    }

    /**
     * 404 Not Found Helper
     */
    public static <T> ApiResponse<T> notFound(String message) {
        return new ApiResponse<>(404, false, message, null, null);
    }

    /**
     * 401 Unauthorized Helper
     */
    public static <T> ApiResponse<T> unauthorized(String message) {
        return new ApiResponse<>(401, false, message, null, null);
    }

    /**
     * 403 Forbidden Helper
     */
    public static <T> ApiResponse<T> forbidden(String message) {
        return new ApiResponse<>(403, false, message, null, null);
    }

    /**
     * 500 Internal Server Error Helper
     */
    public static <T> ApiResponse<T> internalServerError(String message) {
        return new ApiResponse<>(500, false, message, null, null);
    }

    /**
     * 501 Not Implemented (Useful for stubbed out roadmap features)
     */
    public static <T> ApiResponse<T> notImplemented(String message) {
        return new ApiResponse<>(501, false, message, null, null);
    }

    /**
     * 503 Service Unavailable (Used when external dependencies like SMTP or LDAP are down)
     */
    public static <T> ApiResponse<T> serviceUnavailable(String message) {
        return new ApiResponse<>(503, false, message, null, null);
    }

    // ==========================================
    // Standard Getters and Setters for ApiResponse
    // ==========================================

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public T getData() { return data; }
    public void setData(T data) { this.data = data; }

    public PaginationMeta getPagination() { return pagination; }
    public void setPagination(PaginationMeta pagination) { this.pagination = pagination; }
}
