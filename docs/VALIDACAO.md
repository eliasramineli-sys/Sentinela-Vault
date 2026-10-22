# Verificação da entrega — 22/10/2026

Ambiente utilizado: Linux, OpenJDK 17.0.20, execução local em CPU. A verificação local descrita aqui precedeu a publicação no GitHub. Não houve teste manual em um computador Windows.

## Comandos executados

```text
java Build.java
java -Djava.awt.headless=true -jar dist/sentinela-vault-0.1.0.jar
```

O build foi concluído e gerou o JAR executável. Os **23 testes do núcleo passaram**, usando criptografia, derivação de chave e arquivos reais em pastas temporárias, com dados exclusivamente fictícios. Os arquivos temporários do teste são eliminados ao final.

## Cobertura observada

| Verificação | Resultado |
| --- | --- |
| Cadastro, gravação e reabertura | Dados preservados e campos pessoais ausentes em texto simples no arquivo |
| Senha incorreta | Acesso recusado e trava liberada para nova tentativa |
| Alteração de ciphertext e cabeçalho | Abertura recusada |
| Nonce por gravação | Valores diferentes nas gravações examinadas |
| Arquivo de destino já existente | Preservado sem sobrescrita |
| Duas sessões no mesmo arquivo | Segunda sessão recusada |
| Backup | Cópia cifrada reaberta com dados preservados; sem substituir arquivos existentes |
| Troca de senha | Nova senha abre o cofre atual; antiga deixa de abri-lo; backup anterior conserva senha antiga |
| Expiração da sessão | Operações recusadas pelo serviço após o prazo, mesmo sem interface |
| Encerramento explícito | Operações posteriores recusadas |
| Arrays de senha | Limpos nos caminhos testados de sucesso e falha |
| Alteração externa antes de salvar | Atualização recusada, estado externo e estado da sessão preservados |
| Edição e exclusão | Exigem confirmação válida da senha |
| Cinco confirmações incorretas | Sessão encerrada |
| Validação | Senha curta, senha em branco e campos excessivos recusados |
| Parsing | Arquivo truncado, custo KDF desconhecido e contagens/tamanhos excessivos recusados |
| Unicode | Perfil e dados preservados |
| Histórico | Somente tipos genéricos de alterações e datas |
| Espera progressiva | Janela de espera iniciada e aumentada conforme falhas |
| Links simbólicos | Abertura como arquivo de cofre recusada no ambiente Linux testado |
| JAR em ambiente sem tela | Mensagem de necessidade de ambiente gráfico apresentada |

O teste de nonce observa amostras de funcionamento; não demonstra ausência de colisões em qualquer sequência possível. Os testes de proteção e parsing também não substituem uma revisão independente ou fuzzing abrangente.

## Interface e plataformas

A interface foi compilada com sucesso. A janela **não foi validada visualmente**: o Java disponível neste ambiente é uma distribuição headless sem a biblioteca nativa AWT/X11 necessária. A tentativa de disponibilizar um servidor gráfico virtual não forneceu um ambiente desktop Java utilizável.

Há um executor opcional `DesktopSmokeTest.java`, compilado separadamente para conferir sua sintaxe, que exercita abertura da interface, acesso com um cofre fictício, identificador mascarado e evento de bloqueio ao minimizar. Esse teste **não foi concluído aqui** e não é contado nos 23 testes aprovados.

Em um sistema com interface gráfica e JDK completo, depois de `java Build.java`, ele pode ser executado no PowerShell:

```powershell
java -cp "build/classes;build/test-classes" br.com.sentinela.DesktopSmokeTest
```

No Linux/macOS, use `:` em vez de `;` no classpath. O teste não cria capturas de tela por padrão; um caminho opcional só deve ser fornecido para testes internos com dados fictícios.

O workflow GitHub Actions incluído configura testes em Windows/Linux e Java 17/21. Consulte a aba Actions para o resultado remoto; esta seção registra a verificação local inicial. Não foram validados manualmente NTFS, pastas de rede, macOS, instalador Windows, cenários de crash físico ou comprometimento do sistema operacional.

## Natureza da verificação

Não há certificação de segurança bancária, auditoria externa ou afirmação de conformidade regulatória. A entrega demonstra controles implementados e exercitados no escopo descrito. A avaliação inicial deve usar dados fictícios.
