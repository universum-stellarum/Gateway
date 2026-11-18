# Polaris

> **Polaris**, a Estrela Polar, fica quase exatamente sobre o polo celeste norte, por isso
> parece quase parada enquanto o resto do céu gira ao seu redor. Navegadores a usam como
> referência de norte há séculos. Está a cerca de 430 anos-luz da Terra e é uma estrela
> pulsante do tipo Cefeida, cujo brilho varia pouco e de forma regular.

Gateway da API Universum, com Java 21 e Spring Cloud Gateway. Encaminha autenticação e
usuários ao Antares e valida os access tokens JWT pelas chaves públicas do serviço.

## Ambientes e Config Server

As variáveis `APP_ENV` e `CONFIG_SERVER_URL` são obrigatórias, sem valores de fallback.
Use `APP_ENV=dev` ou `APP_ENV=prod`.
O Polaris segue o bootstrap do Antares: Spring Cloud Config Client, perfil selecionado
por `APP_ENV` e import obrigatório do Config Server.

| `APP_ENV` | Configuração consultada | Domínio público | Rede interna |
| --- | --- | --- | --- |
| `dev` | `http://config-server.service.internal/polaris/dev` | `development.coeur.app.br` | `universum-dev` |
| `prod` | `http://config-server.service.internal/polaris/prod` | `production.coeur.app.br` | `universum-prod` |

`src/main/resources/application.yaml` contém somente o bootstrap: nome `polaris`, perfil
e endereço do Config Server. Porta, URLs do Antares e JWKS, emissor JWT, endpoints POST
públicos, origens CORS e timeouts HTTP vêm do YAML remoto. Não há perfil padrão de produção.

Para iniciar, defina no IntelliJ ou no Environment do Dokploy:

```dotenv
APP_ENV=dev
CONFIG_SERVER_URL=http://config-server.service.internal
```

Para produção, use `APP_ENV=prod`. Na execução local, também é possível copiar
[.env.example](.env.example) para `.env`; o Spring importa esse arquivo opcionalmente.
Arquivos `.env` privados são ignorados pelo Git.

Defina `CONFIG_SERVER_URL` com a URL base, como `http://config-server.service.internal`
ou `http://localhost:8888` para um servidor local.
Não inclua `/polaris/dev` ou `/polaris/prod` nessa variável:
o cliente Spring monta o caminho usando o nome da aplicação e o perfil.
O hostname deve resolver e estar acessível a partir do processo/container do Polaris.

O import remoto não usa `optional:`. Se o servidor estiver indisponível, a inicialização
falha. Após alterar a configuração remota, reinicie o Polaris para recarregar os valores;
não há atualização automática por Cloud Bus ou endpoint de refresh.

## Arquivos para o repositório de secrets

Copie estes arquivos para a raiz do repositório consultado pelo Config Server:

- [polaris-dev.yaml](examples/config-repo/polaris-dev.yaml)
- [polaris-prod.yaml](examples/config-repo/polaris-prod.yaml)

Eles contêm valores completos, sem depender das antigas variáveis `SERVER_PORT`,
`IDENTITY_URL`, `JWT_JWK_SET_URI`, `AUTH_PUBLIC_POST_PATHS` e `CORS_ALLOWED_ORIGINS`.
Os exemplos não contêm credenciais: o Polaris valida JWTs com as chaves públicas do Antares.

O `issuer-uri` precisa coincidir exatamente com o emissor dos tokens do Antares em cada
ambiente. As origens em `gateway.cors.allowed-origins` devem ser as do frontend; os arquivos
preservam os domínios existentes. Para várias origens ou endpoints públicos, use valores
separados por vírgula, mantendo o formato esperado pela configuração de segurança.

Mantenha `server.port: 8080` nos dois arquivos: Compose, healthcheck da imagem e domínio
no Dokploy usam essa porta interna. Se decidir mudar a porta, ajuste também esses itens.
Os arquivos em `examples/config-repo` são referências para publicação no outro repositório;
a aplicação não os carrega como fallback local.

## Comunicação por aliases de rede

O Polaris e o Antares de cada ambiente devem entrar na mesma rede Docker.
O Compose do Antares já declara o alias `antares`, usado pelos YAMLs remotos:

```yaml
gateway:
  services:
    identity-url: http://antares:8080
```

O JWKS usa `http://antares:8080/.well-known/jwks.json`.
O alias resolve para a instância de desenvolvimento em `universum-dev` e para a instância
de produção em `universum-prod`. Mantenha cada instância somente na rede do seu ambiente.
Os nomes de containers gerados pelo Dokploy podem mudar; as URLs usam o alias estável.

## Dokploy

Crie as redes compartilhadas uma vez no servidor, caso ainda não existam:

```sh
docker network create universum-dev
docker network create universum-prod
```

1. Publique `polaris-dev.yaml` e `polaris-prod.yaml` no repositório de secrets antes do deploy.
2. Crie uma aplicação Docker Compose do Polaris por ambiente usando [compose.yaml](compose.yaml).
3. Defina `APP_ENV=dev` ou `APP_ENV=prod` e `CONFIG_SERVER_URL=http://config-server.service.internal`
   no Environment e remova as antigas variáveis de configuração do gateway.
4. Configure o domínio do ambiente no serviço `polaris`, porta `8080`, com HTTPS.
5. Confira no Preview Compose a rede `universum-dev` ou `universum-prod`, o alias `antares`
   no serviço de identidade e o acesso ao hostname `config-server.service.internal`.
6. Faça o deploy e confira `/actuator/health` pelo domínio do ambiente.

O Dokploy gerencia a rede de ingresso do Traefik e pode adicionar uma rede própria.
Ela pode coexistir com a rede `universum-*` usada na comunicação entre serviços.
O Compose não fixa o nome da rede do Traefik nem publica a porta no host.

É possível validar o Compose sem iniciar serviços:

```sh
docker compose --env-file .env.example config --quiet
APP_ENV=prod docker compose --env-file .env.example config --quiet
```

No PowerShell, defina `$env:APP_ENV='prod'` antes do segundo comando.

## Rotas e autenticação

O Polaris encaminha `/api/v1/auth/**`, `/api/v1/users/**` e `/.well-known/jwks.json`
ao Antares. Os endpoints POST públicos vêm de `gateway.security.public-post-paths`.
As demais rotas de API exigem JWT válido, com assinatura, expiração e emissor do ambiente.
`GET /actuator/health` é público; os demais caminhos do Actuator são bloqueados.

Login, refresh e logout podem ser encaminhados mesmo com um access token expirado.
O Antares valida as credenciais dessas operações. O refresh token é enviado em JSON;
o frontend deve substituir os dois tokens retornados na renovação.

## Validação

```sh
./mvnw clean verify
docker build -t universum/polaris:validation .
```

No Windows, use `mvnw.cmd` para os comandos Maven.
Os testes de configuração usam um servidor HTTP local com o contrato do Config Server,
servindo os dois YAMLs de `examples/config-repo`, e verificam a seleção de ambiente e a
falha quando o servidor está indisponível. Os testes de rotas usam configuração isolada,
um upstream HTTP de teste e JWTs reais. Não dependem do Config Server ou Antares externos.

Referência: [Spring Cloud Config Client](https://docs.spring.io/spring-cloud-config/reference/client.html).

## Licença

Este projeto é distribuído sob a [licença MIT](LICENSE).
