# RITMO Android Nativo — 2.0 (projeto)

Projeto **Android nativo em Kotlin**. Não utiliza HTML, WebView, JavaScript, páginas web ou domínio. O aplicativo precisa ser compilado e instalado como APK; **este pacote não contém APK compilado**.

## Funcionalidades

- Perfis por aparelho, com dados isolados na base SQLite local (sem login em nuvem)
- Peso e percentual de alteração desde a pesagem mais antiga; gráfico nativo
- Calendário de registros, lançamento de hábitos, medidas e sintomas
- Cronômetro de jejum que persiste no aparelho, sem recompensa por horas adicionais
- Controle das aplicações **realmente realizadas** de tirzepatida (dose, local, lote e notas)
- XP e níveis por consistência em registrar cuidados, não por maior emagrecimento/jejum
- Importação/exportação JSON do perfil (compatível com o histórico RITMO v1)
- Importador CSV Fitdays, condicionado às colunas de data e peso
- Health Connect Android: leitura consentida de peso, percentual de gordura, massa magra e água corporal, **somente se os dados estiverem presentes nele**. Não há API oficial Fitdays ou conexão direta com sua conta.
- Importador genérico de CSV de jejum com colunas start/end ou início/fim; **o formato real de exportação do No Diet ainda não foi validado**. O link de instalação não contém sessões pessoais.

## Construção do APK (gratuita, sem domínio)

### GitHub Actions
1. Crie repositório privado no GitHub, por exemplo `ritmo-android`.
2. Envie **o conteúdo deste ZIP** para a raiz, preservando os diretórios ocultos `.github/`.
3. Vá até **Actions → RITMO - compilar APK nativo debug → Run workflow**.
4. Se o workflow finalizar com sucesso, em **Artifacts** baixe `RITMO-Android-debug` e extraia `app-debug.apk`.
5. No Android, abra o APK e autorize a instalação para sua origem, caso deseje instalá-lo.

### Android Studio
Abra a pasta como projeto, sincronize o Gradle e execute `app` ou `Build > Build APK(s)`.

Dependências: JDK 17, Gradle 8.10.2, Android SDK 35; Gradle e o Android SDK podem ser baixados automaticamente no GitHub Actions. Android mínimo 9 (API 28). Health Connect integrado no sistema no Android 14+; versões anteriores podem precisar instalar o Health Connect.

## Importar seu histórico pessoal

**Não publique o JSON com seus dados de saúde no GitHub**, mesmo se repositório privado, se não for necessário. Transfira o arquivo pessoal separadamente para o telefone.

No APK: crie seu perfil → Integrações → **Importar histórico RITMO (.json)** → selecione o arquivo entregue separadamente. O importador adiciona registros que ainda não existem (identificador estável) e ignora os duplicados. Não substitui automaticamente as pesagens existentes.

## Sincronizar Fitdays

- Se suas pesagens estiverem disponíveis no Health Connect, use **Conectar e sincronizar Health Connect**, autorize acesso e execute sincronização.
- Se não estiverem: no Fitdays, exporte o relatório em CSV e use **Importar CSV do Fitdays**. Os nomes reais das colunas podem variar; se não reconhecidos, o app informa a incompatibilidade sem criar dados fictícios.
- A leitura do Health Connect abrange 29 dias por padrão. O Android exige permissão adicional para períodos anteriores; esse histórico pode ser importado via JSON/CSV.
- Para impedir que medidas do Android sejam gravadas por engano em outro perfil, o Health Connect se vincula a um único perfil local neste aparelho.

## Jejum e No Diet

O importador suporta CSV genérico com início/fim, porém não há integração autenticada com serviços da Leap. Para migrar seu histórico, é necessário obter a exportação real do No Diet, se disponível. **Sem arquivo do próprio aplicativo, as sessões pessoais de jejum permanecem vazias no JSON**.

## Segurança

- Dados de saúde armazenados apenas localmente. Android isolará o banco da área de outros apps, mas não há autenticação de usuário própria nem criptografia adicional do SQLite.
- Os backups `.json` exportados são texto simples; trate-os como documento sensível.
- O APK debug não é release de produção. Para distribuição permanente é necessário assinar um release com chave própria, testar no aparelho e revisar exigências da Play Store.
- Registros de medicação e jejum são ferramentas organizacionais, não prescrições ou orientações de conduta.

## Limitações técnicas

**Este pacote foi escrito e inspecionado, mas não compilado/validado em emulador neste ambiente**, que não possui Android SDK. O workflow de Actions realiza a compilação externa; eventuais erros deverão ser corrigidos a partir do log do build. Sem backend, contas de aparelhos distintos não sincronizam automaticamente.
