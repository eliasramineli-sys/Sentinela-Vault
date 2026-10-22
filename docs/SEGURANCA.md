# Modelo de proteção e formato

## Objetivo

O Sentinela Vault 0.1.0 é um projeto de portfólio que demonstra controles de proteção de dados locais. A inspiração bancária se refere à experiência de acesso e confirmação de operações. Não há auditoria independente, certificação, validação FIPS, alegação de conformidade LGPD ou equivalência com sistemas bancários.

Use dados fictícios na avaliação inicial. Antes de considerar uso sensível, o código, empacotamento e distribuição precisam de revisão independente e de testes específicos nos sistemas de destino.

## Proteções implementadas

| Controle | Implementação |
| --- | --- |
| Derivação da chave | PBKDF2WithHmacSHA256; 600.000 iterações; salt de 32 bytes; chave de 256 bits |
| Criptografia autenticada | AES/GCM/NoPadding; nonce aleatório de 12 bytes por gravação; tag de 128 bits |
| Metadados internos | Perfil, campos, identificadores e atividade criptografados no payload |
| Integridade | Cabeçalho fornecido como AAD; conteúdo entregue ao codec somente após autenticação GCM |
| Parsing | Formato binário próprio e versionado; comprimentos e contagens limitados; sem ObjectInputStream |
| Limites | Arquivo até 8 MiB, até 1.000 itens e 100 eventos; parâmetros KDF aceitos somente para a versão conhecida |
| Acesso a arquivos | CREATE_NEW para cofre/backup; NOFOLLOW_LINKS na abertura; trava por arquivo de sessão |
| Atualização | Comparação SHA-256 com o estado aberto, temporário cifrado, flush e substituição ATOMIC_MOVE |
| Sessão | Chave somente em memória durante a sessão; encerramento explícito e inatividade monotônica de 2 minutos |
| Operações sensíveis | Nova derivação da chave a partir da senha confirmada e comparação com MessageDigest.isEqual |
| Redução de exposição | Segredo fora da tabela; identificador mascarado; janela de revelação limitada a 30 segundos |

PBKDF2 foi escolhido por estar disponível no JDK sem dependências externas. A referência OWASP sugere Argon2id como primeira opção geral e fornece parâmetros para PBKDF2. A escolha desta implementação não implica certificação do algoritmo, do provedor ou do produto.

As senhas recebidas pelas APIs do serviço são consumidas: os arrays são limpos no fim das operações. A aplicação não persiste um hash de login separado: o desbloqueio exige que a chave derivada autentique corretamente o payload cifrado. A senha mestre não é gravada.

## Formato do arquivo

Todos os inteiros do cabeçalho seguem big-endian, conforme ByteBuffer.

| Offset | Bytes | Conteúdo |
| --- | --- | --- |
| 0 | 8 | Magic ASCII SENTVA01 |
| 8 | 4 | Versão 1 |
| 12 | 4 | Iterações PBKDF2: 600000 |
| 16 | 32 | Salt aleatório |
| 48 | 12 | Nonce aleatório GCM |
| 60 | 4 | Tamanho do ciphertext com tag |
| 64 | variável | Payload cifrado seguido da tag GCM |

Os 64 bytes do cabeçalho são autenticados como AAD. O tamanho total deve coincidir com o tamanho declarado, e arquivos com versão/parâmetros desconhecidos são recusados antes de executar a KDF. Não há downgrade automático de custo criptográfico.

Dentro do payload ficam o nome do perfil, registros e eventos de alteração, codificados com limites por campo. A mesma chave é usada ao longo da sessão, com novos nonces aleatórios. A troca de senha cria um novo salt e nova chave e recriptografa o arquivo atual. O limite pequeno de volume e a geração segura de nonces reduzem o número de operações por chave; não há garantia matemática de ausência de colisões aleatórias.

## Arquivos e falhas

Em sistemas POSIX, arquivos criados pelo app usam permissão 600. No Windows, valem as ACLs herdadas da pasta: o aplicativo não configura nem audita permissões NTFS. Guarde o arquivo em uma pasta privada da sua conta e teste o comportamento do sistema de destino.

A criação inicial usa criação exclusiva e pode deixar um novo arquivo incompleto se o processo ou computador falhar durante essa primeira escrita. O programa não sobrescreve um cofre preexistente para criar outro. Nas atualizações, o arquivo temporário contém apenas conteúdo já cifrado e é movido atomicamente; se o sistema de arquivos não oferecer essa operação, a atualização é recusada. Flush do arquivo e rename não substituem uma estratégia de backup nem garantem persistência após qualquer falha de hardware.

A trava é cooperativa e depende de suporte do sistema de arquivos. O hash detecta alterações realizadas antes da verificação de gravação; não oferece exclusão contra um adversário com controle do sistema operacional entre essa verificação e a substituição. Cofres copiados ou restaurados são arquivos independentes, não sincronizados.

## Ameaças fora do escopo

- Malware, keyloggers, administração do computador por terceiros e execução de código modificado.
- Leitura de memória da JVM, swap, hibernação, dumps e cópias internas dos provedores criptográficos.
- Captura de tela, observação de tela desbloqueada ou cópia manual para a área de transferência.
- Quebra de senhas fracas por tentativa offline contra uma cópia cifrada.
- Sincronização concorrente por pastas de rede/nuvem, recuperação de desastre e problemas específicos de armazenamento.
- Rollback para um arquivo anterior autenticado, exclusão física em SSDs e eliminação de cópias externas.
- MFA, biometria, HSM, keystore do sistema, gerenciamento centralizado de usuários e controles financeiros.

O histórico registra alterações de itens e senha; não é log de acessos malsucedidos nem log externo inviolável. Tentativas incorretas não são gravadas com nomes, senhas ou dados pessoais. A espera da interface é um controle do processo e não uma defesa contra acesso offline.

## Evoluções possíveis, ainda não implementadas

Revisão criptográfica independente, integração com keystore do sistema operacional, assinatura/distribuição verificável, testes de interface em Windows real, avaliação de um KDF com resistência baseada em memória e política de migração de versões. Não há funcionalidades futuras simuladas na interface.
