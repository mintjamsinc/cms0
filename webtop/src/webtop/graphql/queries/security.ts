/**
 * GraphQL documents for account security (second factors): the
 * `userSecurity` query and the TOTP / passkey enrollment mutations served by
 * the platform schema (security-schema.graphqls).
 */

const MUTATION_ERROR_FIELDS = `
  field
  message
  code
`;

const PASSKEY_FIELDS = `
  id
  displayName
  created
  lastUsed
  transports
  backedUp
`;

export const USER_SECURITY_FIELDS = `
  username
  totpEnabled
  backupCodesRemaining
  passkeysAvailable
  passkeys {
    ${PASSKEY_FIELDS}
  }
`;

export const SECURITY_QUERIES = {
  USER_SECURITY: `
    query UserSecurity($username: String!) {
      userSecurity(username: $username) {
        ${USER_SECURITY_FIELDS}
      }
    }
  `,
};

export const SECURITY_MUTATIONS = {
  BEGIN_TOTP_ENROLLMENT: `
    mutation BeginTotpEnrollment($input: BeginTotpEnrollmentInput!) {
      beginTotpEnrollment(input: $input) {
        secret
        otpauthUri
        issuer
        accountName
        errors {
          ${MUTATION_ERROR_FIELDS}
        }
      }
    }
  `,

  CONFIRM_TOTP_ENROLLMENT: `
    mutation ConfirmTotpEnrollment($input: ConfirmTotpEnrollmentInput!) {
      confirmTotpEnrollment(input: $input) {
        backupCodes
        security {
          ${USER_SECURITY_FIELDS}
        }
        errors {
          ${MUTATION_ERROR_FIELDS}
        }
      }
    }
  `,

  DISABLE_TOTP: `
    mutation DisableTotp($input: DisableTotpInput!) {
      disableTotp(input: $input) {
        security {
          ${USER_SECURITY_FIELDS}
        }
        errors {
          ${MUTATION_ERROR_FIELDS}
        }
      }
    }
  `,

  REGENERATE_BACKUP_CODES: `
    mutation RegenerateBackupCodes($input: RegenerateBackupCodesInput!) {
      regenerateBackupCodes(input: $input) {
        backupCodes
        security {
          ${USER_SECURITY_FIELDS}
        }
        errors {
          ${MUTATION_ERROR_FIELDS}
        }
      }
    }
  `,

  BEGIN_PASSKEY_REGISTRATION: `
    mutation BeginPasskeyRegistration($input: BeginPasskeyRegistrationInput!) {
      beginPasskeyRegistration(input: $input) {
        options
        errors {
          ${MUTATION_ERROR_FIELDS}
        }
      }
    }
  `,

  FINISH_PASSKEY_REGISTRATION: `
    mutation FinishPasskeyRegistration($input: FinishPasskeyRegistrationInput!) {
      finishPasskeyRegistration(input: $input) {
        passkey {
          ${PASSKEY_FIELDS}
        }
        security {
          ${USER_SECURITY_FIELDS}
        }
        errors {
          ${MUTATION_ERROR_FIELDS}
        }
      }
    }
  `,

  RENAME_PASSKEY: `
    mutation RenamePasskey($input: RenamePasskeyInput!) {
      renamePasskey(input: $input) {
        security {
          ${USER_SECURITY_FIELDS}
        }
        errors {
          ${MUTATION_ERROR_FIELDS}
        }
      }
    }
  `,

  DELETE_PASSKEY: `
    mutation DeletePasskey($input: DeletePasskeyInput!) {
      deletePasskey(input: $input) {
        security {
          ${USER_SECURITY_FIELDS}
        }
        errors {
          ${MUTATION_ERROR_FIELDS}
        }
      }
    }
  `,
};
