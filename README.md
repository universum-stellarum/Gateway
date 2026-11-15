# Polaris

Gateway da API Universum, com Java 21 e Spring Cloud Gateway. Encaminha autenticação e
usuários ao Antares e valida os access tokens JWT pelas chaves públicas do serviço.

## Ambientes

| Ambiente | Domínio público | `APP_ENV` | Rede interna |
| --- | --- | --- | --- |
| Desenvolvimento | `development.coeur.app.br` | `dev` | `universum-dev` |
| Produção | `production.coeur.app.br` | `prod` | `universum-prod` |

`application.yaml` contém a configuração comum e o emissor JWT de produção.
`application-dev.yaml` sobrescreve o emissor para desenvolvimento. O Compose seleciona
o perfil Spring a partir de `APP_ENV`.

## Comunicação por aliases de rede

Os nomes de containers gerados pelo Dokploy podem mudar a cada implantação. Eles não
devem ser gravados nas URLs dos serviços, e não é necessário definir `container_name`.

A comunicação usa um **alias DNS na rede Docker**. O Compose do Antares deve declarar:

```yaml
services:
  antares:
    networks:
      services:
        aliases:
          - antares

networks:
  services:
    external: true
    name: universum-${APP_ENV:?Defina APP_ENV como dev ou prod}
```

Esse trecho mostra apenas a configuração de rede a aplicar no serviço Antares.
O Polaris entra na mesma rede e utiliza:

```dotenv
IDENTITY_URL=http://antares:8080
JWT_JWK_SET_URI=http://antares:8080/.well-known/jwks.json
```

O alias `antares` resolve para a instância de desenvolvimento em `universum-dev` e para
a instância de produção em `universum-prod`. Os dois ambientes usam o mesmo endereço
interno, com resolução separada por rede. Mantenha cada instância somente na rede de
serviços do seu ambiente: dois serviços diferentes com o mesmo alias na mesma rede
tornam a resolução ambígua.

O endereço deve usar a porta **interna** do Antares. Containers distintos podem usar
`8080` simultaneamente; o Compose não publica essa porta no host.

## Configuração

Todas as variáveis abaixo são obrigatórias. Não há valores de fallback na aplicação;
os valores dos arquivos de exemplo precisam ser fornecidos ao ambiente de execução.

| Variável | Finalidade |
| --- | --- |
| `APP_ENV` | Seleciona `dev` ou `prod`, o perfil Spring e a rede Docker |
| `SERVER_PORT` | Porta interna do Polaris; os exemplos usam `8080` |
| `IDENTITY_URL` | Endereço interno do Antares |
| `JWT_JWK_SET_URI` | Endereço do JWKS com as chaves públicas do Antares |
| `AUTH_PUBLIC_POST_PATHS` | Caminhos POST públicos de login, refresh e logout, separados por vírgula |
| `CORS_ALLOWED_ORIGINS` | Origens reais do frontend, separadas por vírgula |

Use [.env.example](.env.example), [.env.dev.example](.env.dev.example) ou
[.env.prod.example](.env.prod.example) como modelo. Arquivos `.env` privados são
ignorados pelo Git. Configure as origens reais do frontend; elas podem ser diferentes
do domínio público da API.

## Dokploy

Crie as redes compartilhadas uma vez no servidor, caso ainda não existam:

```sh
docker network create universum-dev
docker network create universum-prod
```

1. Crie uma aplicação Docker Compose do Polaris para cada ambiente, usando o
   [compose.yaml](compose.yaml) da raiz deste repositório.
2. Configure as variáveis do exemplo correspondente no Dokploy.
3. Configure cada domínio no serviço `polaris`, na porta indicada por `SERVER_PORT`,
   com HTTPS. Os exemplos usam a porta interna `8080`.
4. Confira no **Preview Compose** se a rede `universum-dev` ou `universum-prod` foi
   preservada. Na implantação do Antares, confira também o alias `antares`.
5. Faça o deploy e confira `/actuator/health` pelo domínio do ambiente.

O Dokploy gerencia a rede de ingresso do Traefik e pode adicionar uma rede própria.
Essa rede pode coexistir com a rede `universum-*`, usada na comunicação entre serviços.
O Compose não fixa a rede do Traefik nem exige que ela se chame `dokploy-network`.

O comando `docker ps` mostra nomes de containers, mas não seus aliases. Para inspecionar
as redes e testar a comunicação, use os IDs atuais dos containers:

```sh
docker inspect <id-do-antares> --format '{{json .NetworkSettings.Networks}}'
docker inspect <id-do-polaris> --format '{{json .NetworkSettings.Networks}}'
docker exec <id-do-polaris> curl --fail --silent http://antares:8080/actuator/health
```

Antes do deploy, também é possível validar o Compose sem iniciar serviços:

```sh
docker compose --env-file .env.dev.example config --quiet
docker compose --env-file .env.prod.example config --quiet
```

## Rotas e autenticação

O Polaris encaminha `/api/v1/auth/**`, `/api/v1/users/**` e `/.well-known/jwks.json`
ao Antares. Os endpoints POST públicos são definidos por `AUTH_PUBLIC_POST_PATHS`.
As demais rotas de API exigem JWT válido, com assinatura, expiração e emissor do ambiente.
`GET /actuator/health` é público; os demais caminhos do Actuator são bloqueados.

Login, refresh e logout podem ser encaminhados mesmo com um access token expirado.
O Antares valida as credenciais dessas operações. O refresh token é enviado em JSON;
o frontend deve substituir os dois tokens retornados na renovação.

## Validação

```sh
./mvnw verify
docker build -t universum/polaris:validation .
```

No Windows, use `mvnw.cmd` para os comandos Maven. Os testes utilizam upstream HTTP
de teste e JWTs reais, sem depender de uma instância externa do Antares.

## Licença

Este projeto é distribuído sob a [licença MIT](LICENSE).

Referência de rede: [aliases no Docker Compose](https://docs.docker.com/reference/compose-file/services/#aliases).
