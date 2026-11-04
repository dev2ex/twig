/* Exercise the real compound callbacks without a server or Android runtime. */
#include <assert.h>
#include <stdarg.h>
#include "libsmb2.c"

static char error_text[512];
static int error_status;
static int result;

void smb2_set_nterror(struct smb2_context *ctx, int status, const char *format, ...)
{
        va_list args;
        va_start(args, format);
        vsnprintf(error_text, sizeof(error_text), format, args);
        va_end(args);
        error_status = status;
}

static void completed(struct smb2_context *ctx, int status, void *data, void *private_data)
{
        result = status;
}

static void check(uint32_t open_status, uint32_t rename_status, uint32_t close_status,
                  uint32_t expected)
{
        struct rename_cb_data *data = calloc(1, sizeof(*data));
        assert(data);
        data->cb = completed;
        error_text[0] = '\0';
        error_status = 0;
        result = 123;
        rename_cb_1(NULL, open_status, NULL, data);
        rename_cb_2(NULL, rename_status, NULL, data);
        rename_cb_3(NULL, close_status, NULL, data);
        assert(result == -nterror_to_errno(expected));
        if (expected == SMB2_STATUS_SUCCESS) {
                assert(error_text[0] == '\0');
        } else {
                assert((uint32_t)error_status == expected);
                assert(strstr(error_text, nterror_to_str(expected)));
                assert(strstr(error_text, "Rename failed"));
        }
}

static void check_create_delete(uint32_t open_status, uint32_t close_status, uint32_t expected)
{
        struct create_cb_data *data = calloc(1, sizeof(*data));
        assert(data);
        data->cb = completed;
        error_text[0] = '\0';
        error_status = 0;
        result = 123;
        create_cb_1(NULL, open_status, NULL, data);
        create_cb_2(NULL, close_status, NULL, data);
        assert(result == -nterror_to_errno(expected));
        if (expected == SMB2_STATUS_SUCCESS) {
                assert(error_text[0] == '\0');
        } else {
                assert((uint32_t)error_status == expected);
                assert(strstr(error_text, nterror_to_str(expected)));
        }
}

int main(void)
{
        check(0, 0, 0, 0);
        check(SMB2_STATUS_ACCESS_DENIED, SMB2_STATUS_INVALID_HANDLE,
              SMB2_STATUS_INVALID_HANDLE, SMB2_STATUS_ACCESS_DENIED);
        check(0, SMB2_STATUS_SHARING_VIOLATION, 0, SMB2_STATUS_SHARING_VIOLATION);
        check(0, SMB2_STATUS_OBJECT_NAME_COLLISION, SMB2_STATUS_INVALID_HANDLE,
              SMB2_STATUS_OBJECT_NAME_COLLISION);
        check(0, 0, SMB2_STATUS_ACCESS_DENIED, SMB2_STATUS_ACCESS_DENIED);
        check_create_delete(0, 0, 0);
        check_create_delete(SMB2_STATUS_ACCESS_DENIED, SMB2_STATUS_INVALID_HANDLE,
                            SMB2_STATUS_ACCESS_DENIED);
        check_create_delete(SMB2_STATUS_OBJECT_NAME_COLLISION, SMB2_STATUS_INVALID_HANDLE,
                            SMB2_STATUS_OBJECT_NAME_COLLISION);
        check_create_delete(0, SMB2_STATUS_DIRECTORY_NOT_EMPTY, SMB2_STATUS_DIRECTORY_NOT_EMPTY);
        puts("SMB rename/create/delete error callbacks passed");
}
