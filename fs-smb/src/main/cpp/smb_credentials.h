#pragma once

/* libsmb2 distinguishes a NULL password (anonymous NTLM) from an empty
 * password for a named account. Set the password last: user/domain setters
 * may load a password from NTLM_USER_FILE on platforms that support it. */
static inline void twig_smb_set_credentials(struct smb2_context *ctx,
                                           const char *user, const char *password,
                                           const char *domain)
{
    smb2_set_user(ctx, user ? user : "");
    smb2_set_domain(ctx, domain ? domain : "");
    const int anonymous = (!user || !user[0]) && (!password || !password[0]);
    smb2_set_password(ctx, anonymous ? NULL : (password ? password : ""));
}
