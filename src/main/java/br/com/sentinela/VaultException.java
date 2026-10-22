package br.com.sentinela;

/** Falha esperada, com mensagem apropriada para apresentação ao usuário. */
public final class VaultException extends Exception {
    public VaultException(String message) { super(message); }
    public VaultException(String message, Throwable cause) { super(message, cause); }
}
