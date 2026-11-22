/* Live regressions against disposable Samba fixtures; no Android runtime needed. */
#include <assert.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include "smb2.h"
#include "libsmb2.h"
#include "libsmb2-private.h"
#include "libsmb2-dcerpc.h"
#include "libsmb2-dcerpc-srvsvc.h"
#include "smb_credentials.h"

static void check_credentials(void)
{
        struct smb2_context *ctx = smb2_init_context();
        assert(ctx);
        twig_smb_set_credentials(ctx, "", "", "");
        assert(ctx->password == NULL);
        assert(!strcmp(ctx->user, ""));
        twig_smb_set_credentials(ctx, "fixture", "", "");
        assert(ctx->password && !strcmp(ctx->password, ""));
        twig_smb_set_credentials(ctx, "guest", "", "");
        assert(ctx->password && !strcmp(ctx->password, ""));
        twig_smb_set_credentials(ctx, "", "not-empty", "");
        assert(ctx->password && !strcmp(ctx->password, "not-empty"));
        twig_smb_set_credentials(ctx, NULL, NULL, NULL);
        assert(ctx->password == NULL);
        assert(!strcmp(ctx->user, ""));
        ctx->session_flags = SMB2_SESSION_FLAG_IS_GUEST;
        smb2_close_context(ctx);
        assert(ctx->session_flags == 0);
        smb2_destroy_context(ctx);
}

static void roundtrip(struct smb2_context *ctx)
{
        char path[100];
        const char payload[] = "Twig guest SMB roundtrip";
        uint8_t actual[sizeof(payload)] = {0};
        snprintf(path, sizeof(path), "twig-guest-test-%ld.txt", (long)getpid());
        struct smb2fh *fh = smb2_open(ctx, path, O_CREAT | O_EXCL | O_RDWR);
        assert(fh);
        assert(smb2_pwrite(ctx, fh, (const uint8_t *)payload, sizeof(payload), 0) == sizeof(payload));
        assert(smb2_pread(ctx, fh, actual, sizeof(actual), 0) == sizeof(payload));
        assert(!memcmp(payload, actual, sizeof(payload)));
        assert(smb2_close(ctx, fh) == 0);
        struct smb2dir *dir = smb2_opendir(ctx, "");
        assert(dir);
        struct smb2dirent *entry;
        int found = 0;
        while ((entry = smb2_readdir(ctx, dir))) {
                if (!strcmp(path, entry->name)) found = 1;
        }
        smb2_closedir(ctx, dir);
        assert(found);
        assert(smb2_unlink(ctx, path) == 0);
        assert(smb2_stat(ctx, path, &(struct smb2_stat_64){0}) < 0);
}

static void enumerate(struct smb2_context *ctx)
{
        struct srvsvc_NetrShareEnum_rep *rep = smb2_share_enum_sync(ctx, SHARE_INFO_1);
        assert(rep && rep->status == 0);
        struct srvsvc_SHARE_INFO_1_CONTAINER *ctr = &rep->ses.ShareInfo.Level1;
        assert(ctr->Buffer && ctr->Buffer->share_info_1);
        assert(ctr->EntriesRead <= ctr->Buffer->max_count);
        int found = 0;
        for (uint32_t i = 0; i < ctr->EntriesRead; i++) {
                if (!strcmp(ctr->Buffer->share_info_1[i].netname.utf8, "Pub")) found = 1;
        }
        assert(found);
        smb2_free_data(ctx, rep);
}

static void check(const char *label, const char *host, const char *share,
                  const char *user, const char *password, int dialect,
                  int sign, int seal, int success)
{
        struct smb2_context *ctx = smb2_init_context();
        assert(ctx);
        twig_smb_set_credentials(ctx, user, password, "");
        smb2_set_security_mode(ctx, SMB2_NEGOTIATE_SIGNING_ENABLED);
        smb2_set_version(ctx, dialect);
        smb2_set_timeout(ctx, 5);
        smb2_set_sign(ctx, sign);
        smb2_set_seal(ctx, seal);
        int ret = smb2_connect_share(ctx, host, share, NULL);
        if ((ret == 0) != success) {
                fprintf(stderr, "%s: connect=%d error=%s\n", label, ret, smb2_get_error(ctx));
                assert(0);
        }
        if (success) {
                assert(smb2_get_dialect(ctx) == dialect);
                if (!user[0] || !strcmp(user, "guest")) {
                        assert(ctx->session_flags & (SMB2_SESSION_FLAG_IS_GUEST | SMB2_SESSION_FLAG_IS_NULL));
                        assert(ctx->session_key_size == 0);
                } else {
                        assert(!(ctx->session_flags & (SMB2_SESSION_FLAG_IS_GUEST | SMB2_SESSION_FLAG_IS_NULL)));
                        if (sign) assert(ctx->sign);
                        if (seal) assert(ctx->seal);
                }
                if (!strcmp(share, "IPC$")) enumerate(ctx);
                else roundtrip(ctx);
        }
        smb2_destroy_context(ctx);
        printf("PASS %s (SMB %04x)\n", label, dialect);
        fflush(stdout);
}

int main(int argc, char **argv)
{
        assert(argc == 4);
        check_credentials();
        for (int i = 0; i < 2; i++) {
                int dialect = i ? SMB2_VERSION_0311 : SMB2_VERSION_0210;
                check("guest share", argv[1], "Pub", "guest", "", dialect, 0, 0, 1);
                check("guest share enumeration", argv[1], "IPC$", "guest", "", dialect, 0, 0, 1);
                check("anonymous share", argv[2], "Pub", "", "", dialect, 0, 0, 1);
                check("anonymous share enumeration", argv[2], "IPC$", "", "", dialect, 0, 0, 1);
                check("anonymous-only server rejects named guest", argv[2], "Pub", "guest", "", dialect, 0, 0, 0);
                check("named signed share", argv[1], "Private", "fixture", "fixture-pass", dialect, 1, 0, 1);
                check("wrong password", argv[1], "Private", "fixture", "wrong-password", dialect, 0, 0, 0);
                check("guest cannot meet signing requirement", argv[1], "Pub", "guest", "", dialect, 1, 0, 0);
                check("anonymous cannot meet signing requirement", argv[2], "Pub", "", "", dialect, 1, 0, 0);
                check("server requires signing", argv[3], "Private", "fixture", "fixture-pass", dialect, 0, 0, 1);
                check("guest cannot bypass server signing", argv[3], "Pub", "guest", "", dialect, 0, 0, 0);
        }
        check("guest cannot meet encryption requirement", argv[1], "Pub", "guest", "", SMB2_VERSION_0311, 0, 1, 0);
        check("named encrypted share", argv[1], "Private", "fixture", "fixture-pass", SMB2_VERSION_0311, 0, 1, 1);
        puts("SMB guest/anonymous access regressions passed");
}
