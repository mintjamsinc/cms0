/**
 * Account-security service: a user's second factors (TOTP with backup codes,
 * passkeys) through the platform GraphQL API. Mutations that weaken a sign-in
 * or start a new enrollment take the caller's current password; the server
 * verifies it.
 */

import { GraphQLClient, createGraphQLClient } from '../graphql/client.js';
import { SECURITY_QUERIES, SECURITY_MUTATIONS } from '../graphql/queries/security.js';
import type {
  UserSecurity,
  BeginTotpEnrollmentInput,
  TotpEnrollmentPayload,
  ConfirmTotpEnrollmentInput,
  BackupCodesPayload,
  DisableTotpInput,
  RegenerateBackupCodesInput,
  UserSecurityPayload,
  BeginPasskeyRegistrationInput,
  PasskeyRegistrationPayload,
  FinishPasskeyRegistrationInput,
  PasskeyPayload,
  RenamePasskeyInput,
  DeletePasskeyInput,
} from '../graphql/types.js';

export class SecurityServiceGraphQL {
  #client: GraphQLClient;

  constructor(client?: GraphQLClient) {
    this.#client = client ?? createGraphQLClient('system');
  }

  async getUserSecurity(username: string): Promise<UserSecurity | null> {
    const data = await this.#client.query<{ userSecurity: UserSecurity | null }>(
      SECURITY_QUERIES.USER_SECURITY,
      { username }
    );
    return data.userSecurity;
  }

  // =========================================================================
  // TOTP
  // =========================================================================

  async beginTotpEnrollment(input: BeginTotpEnrollmentInput): Promise<TotpEnrollmentPayload> {
    const data = await this.#client.mutation<{ beginTotpEnrollment: TotpEnrollmentPayload }>(
      SECURITY_MUTATIONS.BEGIN_TOTP_ENROLLMENT,
      { input }
    );
    return data.beginTotpEnrollment;
  }

  async confirmTotpEnrollment(input: ConfirmTotpEnrollmentInput): Promise<BackupCodesPayload> {
    const data = await this.#client.mutation<{ confirmTotpEnrollment: BackupCodesPayload }>(
      SECURITY_MUTATIONS.CONFIRM_TOTP_ENROLLMENT,
      { input }
    );
    return data.confirmTotpEnrollment;
  }

  async disableTotp(input: DisableTotpInput): Promise<UserSecurityPayload> {
    const data = await this.#client.mutation<{ disableTotp: UserSecurityPayload }>(
      SECURITY_MUTATIONS.DISABLE_TOTP,
      { input }
    );
    return data.disableTotp;
  }

  async regenerateBackupCodes(input: RegenerateBackupCodesInput): Promise<BackupCodesPayload> {
    const data = await this.#client.mutation<{ regenerateBackupCodes: BackupCodesPayload }>(
      SECURITY_MUTATIONS.REGENERATE_BACKUP_CODES,
      { input }
    );
    return data.regenerateBackupCodes;
  }

  // =========================================================================
  // Passkeys
  // =========================================================================

  async beginPasskeyRegistration(input: BeginPasskeyRegistrationInput): Promise<PasskeyRegistrationPayload> {
    const data = await this.#client.mutation<{ beginPasskeyRegistration: PasskeyRegistrationPayload }>(
      SECURITY_MUTATIONS.BEGIN_PASSKEY_REGISTRATION,
      { input }
    );
    return data.beginPasskeyRegistration;
  }

  async finishPasskeyRegistration(input: FinishPasskeyRegistrationInput): Promise<PasskeyPayload> {
    const data = await this.#client.mutation<{ finishPasskeyRegistration: PasskeyPayload }>(
      SECURITY_MUTATIONS.FINISH_PASSKEY_REGISTRATION,
      { input }
    );
    return data.finishPasskeyRegistration;
  }

  async renamePasskey(input: RenamePasskeyInput): Promise<UserSecurityPayload> {
    const data = await this.#client.mutation<{ renamePasskey: UserSecurityPayload }>(
      SECURITY_MUTATIONS.RENAME_PASSKEY,
      { input }
    );
    return data.renamePasskey;
  }

  async deletePasskey(input: DeletePasskeyInput): Promise<UserSecurityPayload> {
    const data = await this.#client.mutation<{ deletePasskey: UserSecurityPayload }>(
      SECURITY_MUTATIONS.DELETE_PASSKEY,
      { input }
    );
    return data.deletePasskey;
  }
}

// ---------------------------------------------------------------------------
// WebAuthn helpers: the server speaks JSON with base64url binary members; the
// browser API wants ArrayBuffers.
// ---------------------------------------------------------------------------

export function base64UrlToBuffer(value: string): ArrayBuffer {
  const base64 = value.replace(/-/g, '+').replace(/_/g, '/');
  const padded = base64 + '==='.slice(0, (4 - (base64.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes.buffer;
}

export function bufferToBase64Url(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/**
 * Runs `navigator.credentials.create()` with the options the server issued and
 * returns the credential as the JSON the server expects.
 */
export async function createPasskey(options: Record<string, unknown>): Promise<Record<string, unknown>> {
  const publicKey: any = { ...options };
  publicKey.challenge = base64UrlToBuffer(String(options.challenge));
  const user = { ...(options.user as Record<string, unknown>) };
  user.id = base64UrlToBuffer(String(user.id));
  publicKey.user = user;
  publicKey.excludeCredentials = ((options.excludeCredentials as Array<Record<string, unknown>>) || []).map((c) => ({
    ...c,
    id: base64UrlToBuffer(String(c.id)),
  }));

  const credential = (await navigator.credentials.create({ publicKey })) as PublicKeyCredential | null;
  if (!credential) {
    throw new DOMException('No credential was created.', 'NotAllowedError');
  }
  const response = credential.response as AuthenticatorAttestationResponse;
  const transports = typeof response.getTransports === 'function' ? response.getTransports() : [];
  return {
    id: credential.id,
    rawId: bufferToBase64Url(credential.rawId),
    type: credential.type,
    authenticatorAttachment: (credential as any).authenticatorAttachment ?? null,
    response: {
      clientDataJSON: bufferToBase64Url(response.clientDataJSON),
      attestationObject: bufferToBase64Url(response.attestationObject),
      transports,
    },
  };
}

/** Whether this browser can register and use passkeys. */
export function isWebAuthnSupported(): boolean {
  return typeof window !== 'undefined' && !!window.PublicKeyCredential && !!navigator.credentials;
}
