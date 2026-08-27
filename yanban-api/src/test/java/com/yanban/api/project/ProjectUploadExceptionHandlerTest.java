package com.yanban.api.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.yanban.api.error.ApiErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

class ProjectUploadExceptionHandlerTest {

    @Test
    void oversizedMultipartRequestReturnsStablePayloadTooLargeResponse() {
        var response = new ProjectExceptionHandler().uploadTooLarge(
                new MaxUploadSizeExceededException(128L * 1024 * 1024));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isEqualTo(ApiErrorResponse.of(
                "PROJECT_UPLOAD_TOO_LARGE",
                "The selected Project folder is too large. Remove generated or binary files and try again."));
    }

    @Test
    void objectStorageFailureIsNotMislabelledAsPlanFailure() {
        var response = new ProjectExceptionHandler().responseStatus(
                new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Project file upload failed"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isEqualTo(ApiErrorResponse.of(
                "PROJECT_STORAGE_UNAVAILABLE", "Project file upload failed"));
    }
}
