# Sentinela Vault — Java

Cofre desktop de informações pessoais, desenvolvido inteiramente em **Java 17**, com interface Swing e recursos criptográficos do próprio JDK. A navegação e os cuidados de acesso foram inspirados em aplicativos bancários: acesso por senha, sessão com prazo de inatividade, confirmação para operações sensíveis e dados ocultos na listagem.

**Versão 0.1.0 de portfólio.** O projeto não foi auditado por especialistas independentes e não deve ser apresentado como segurança bancária certificada. Comece com dados fictícios. O escopo é um cofre local, sem serviços financeiros, integração com bancos ou sincronização em nuvem.

## O que está implementado

- Criação de perfil e arquivo de cofre com senha mestre de 16 a 256 caracteres.
- Abertura do cofre selecionado mediante senha; o nome do perfil também fica criptografado.
- Cadastro, pesquisa, edição e exclusão de documentos, credenciais, contatos e anotações.
- Campos: título, categoria, identificador/usuário, informação protegida e notas privadas.
- AES-256-GCM para criptografia e autenticação do conteúdo, com nonce aleatório de 96 bits em cada gravação e tag de 128 bits.
- Chave derivada com PBKDF2-HMAC-SHA256, 600.000 iterações e salt aleatório de 32 bytes.
- Confirmação da senha mestre para revelar, editar, excluir, copiar backup e trocar a senha.
- Dados revelados em uma janela que fecha após 30 segundos.
- Bloqueio manual, ao minimizar e após 2 minutos sem interação de teclado/clique com o aplicativo.
- Espera progressiva após cinco falhas de acesso no mesmo processo; cinco confirmações incorretas também encerram a sessão aberta.
- Gerador de senhas aleatórias de 28 caracteres.
- Backup criptografado e abertura de backups como cofres independentes.
- Até 100 eventos de alterações registrados dentro do próprio conteúdo criptografado.
- Trava de arquivo para evitar duas sessões cooperantes no mesmo cofre.
- Verificação de alteração externa antes de salvar; gravação de atualizações por substituição atômica.
- Sem dependências externas, serviços de rede, telemetria ou arquivos de senha em texto simples.

O nome do arquivo, tamanho, salt e parâmetros criptográficos são visíveis no sistema de arquivos. Títulos, categorias, perfil, identificadores, segredos, notas e atividade ficam dentro do payload criptografado.

## Abrir no Windows

Este repositório contém o código-fonte. Instale **JDK 17 ou superior** e Git para clonar o projeto. No PowerShell:

```powershell
git clone https://github.com/eliasramineli-sys/Sentinela-Vault.git
cd Sentinela-Vault
java -version
java Build.java
java -jar .\dist\sentinela-vault-0.1.0.jar
```

Também é possível usar **Code → Download ZIP**, extrair e abrir o PowerShell na pasta que contém `Build.java`. Nesse caso, execute os três comandos Java acima.

O build compila o projeto, executa os testes e gera o JAR em `dist/`. Os arquivos compilados não são versionados no repositório. Quando a compilação e os testes do workflow terminam com sucesso, o JAR é disponibilizado como artefato na [aba Actions](https://github.com/eliasramineli-sys/Sentinela-Vault/actions).

Se você já tem o pacote compilado fornecido separadamente, basta executar `java -jar .\dist\sentinela-vault-0.1.0.jar` com Java 17 ou superior. Não foi criado um instalador `.exe`. No Linux/macOS, use `java -jar dist/sentinela-vault-0.1.0.jar`, em uma sessão gráfica.

## Compilar, testar e gerar o JAR

Para desenvolver, instale um **JDK 17 ou superior**. Somente o JRE pode abrir o JAR, mas não oferece necessariamente o compilador para gerar novos pacotes.

Na raiz do projeto:

```powershell
java Build.java
```

Esse único comando compila as classes para Java 17, executa a suíte de testes e gera `dist/sentinela-vault-0.1.0.jar`. Se algum teste falhar, a geração do novo pacote é interrompida. Não precisa instalar Maven, Gradle ou bibliotecas adicionais.

Comandos separados:

```powershell
java Build.java test
java Build.java package
```

`test` compila e testa; `package` compila e gera o JAR sem executar testes. O comando padrão executa ambos.

Em uma IDE, configure o SDK/JDK 17 ou superior, `src/main/java` como raiz de código e `src/test/java` como raiz de testes. A classe de entrada é `br.com.sentinela.Main`. Os testes têm um executor Java próprio: `br.com.sentinela.SecurityTests`.

## Primeiro uso

1. Clique em **Criar perfil e novo cofre**.
2. Escolha um nome de perfil e uma frase-senha longa e exclusiva; confirme a senha.
3. Salve o novo arquivo `.svault` em uma pasta local da sua conta do sistema operacional. Um arquivo existente não será substituído.
4. Na sessão aberta, clique em **Novo registro**. Faça o primeiro teste com informações fictícias.
5. Selecione um registro para revelar, editar ou excluir. O aplicativo solicitará sua senha mestre.
6. Use **Backup protegido** para salvar uma cópia criptografada em um novo arquivo.
7. Use **Bloquear agora** ao se afastar do computador.

Cada arquivo de cofre representa um perfil independente. O login é a escolha desse arquivo e a senha mestre; não há cadastro em servidor.

## Senha e backup

Não há recuperação por e-mail ou senha administrativa. **Perder a senha mestre significa perder o acesso ao conteúdo.** Uma frase longa e exclusiva é preferível a uma senha curta previsível. Os espaços fazem parte da senha e não são removidos.

Para restaurar, selecione a cópia `.svault` na tela inicial e informe a senha correspondente àquela cópia. A abertura não substitui outro cofre.

Ao trocar a senha, apenas o cofre atual é recriptografado, com novo salt e nova chave. **Backups antigos continuam aceitando a senha anterior** e mantêm os dados existentes quando foram criados. A exclusão de um item também não altera cópias antigas. O projeto não oferece apagamento físico garantido em SSDs, snapshots ou backups do sistema.

## Estrutura

```text
sentinela-vault-java/
├── Build.java
├── README.md
├── src/main/java/br/com/sentinela/
│   ├── Main.java            # Interface Swing e bloqueio visual
│   ├── VaultService.java    # Sessões e operações do cofre
│   ├── VaultData.java       # Modelo e codec binário com limites
│   ├── Crypto.java          # Derivação de chave e AES-GCM
│   ├── VaultFiles.java      # Arquivos, travas e gravação atômica
│   ├── IdleGuard.java       # Controle de inatividade
│   ├── LoginThrottle.java   # Espera entre tentativas no processo
│   └── VaultException.java
├── src/test/java/br/com/sentinela/SecurityTests.java
├── docs/SEGURANCA.md
├── docs/VALIDACAO.md
└── .github/workflows/ci.yml
```

O projeto não usa serialização Java de objetos, banco de dados, navegador, JavaScript, Python ou serviços externos. O build e os testes também são escritos em Java.

## Limites importantes do modelo de proteção

O objetivo é proteger os dados **em repouso**, quando alguém obtém uma cópia do arquivo e não conhece a senha. Um invasor pode tentar adivinhar a senha offline; a espera entre tentativas da interface não limita ataques contra uma cópia do arquivo. Reabrir o programa também reinicia essa espera.

A sessão aberta exibe informações e mantém dados em memória. O aplicativo não protege contra malware, keyloggers, capturas de tela, acesso administrativo, alteração do executável ou comprometimento do sistema operacional. Arrays de senha e chave são limpos quando possível; Strings, cópias internas da JVM/provedores e memória virtual não têm apagamento garantido. A área de transferência do sistema, quando usada manualmente, não é gerenciada pelo aplicativo.

O histórico é um registro local de alterações, não uma trilha de auditoria independente: restaurar um backup antigo também restaura a atividade antiga. Arquivos com autenticação válida não possuem proteção contra rollback para uma versão anterior legítima.

Leia [docs/SEGURANCA.md](docs/SEGURANCA.md) para detalhes do formato, ameaças e restrições. A suíte automatizada é uma verificação de comportamento e regressões; não equivale a uma auditoria de segurança.

## Repositório e verificações

Código-fonte: [eliasramineli-sys/Sentinela-Vault](https://github.com/eliasramineli-sys/Sentinela-Vault).

O workflow configura compilação e testes em Windows/Linux com Java 17 e 21. Consulte a [aba Actions](https://github.com/eliasramineli-sys/Sentinela-Vault/actions) para os resultados remotos; os resultados locais e seus limites estão em [docs/VALIDACAO.md](docs/VALIDACAO.md).

Não publique arquivos `.svault`, backups, chaves ou informações reais. O `.gitignore` exclui arquivos de cofre, travas e saídas do build.

Licença do código: escolha pendente. Nenhuma licença de reutilização foi imposta nesta entrega.

## Referências utilizadas

- [OWASP — armazenamento criptográfico](https://cheatsheetseries.owasp.org/cheatsheets/Cryptographic_Storage_Cheat_Sheet.html)
- [OWASP — derivação lenta de senhas e parâmetros de PBKDF2](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
- [Java 17 — Cipher e uso de GCM](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/javax/crypto/Cipher.html)
- [Java — arquitetura de criptografia](https://docs.oracle.com/en/java/javase/21/security/java-cryptography-architecture-jca-reference-guide.html)
