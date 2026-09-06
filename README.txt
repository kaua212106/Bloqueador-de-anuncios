BLOQUEADOR DE ANÚNCIOS V1.0

Arquivos para colocar na raiz do repositório:
- index.html (enviado separadamente)
- MainActivity.java
- AdBlockVpnService.java
- AndroidManifest.xml
- icone.png

O build-apk.yml é enviado no chat para copiar e colar em:
.github/workflows/build-apk.yml

Como funciona:
- VpnService cria uma VPN local.
- Apenas o endereço DNS virtual 10.10.10.2/32 entra no túnel.
- Consultas bloqueadas recebem NXDOMAIN.
- Consultas permitidas são encaminhadas para 1.1.1.1, 8.8.8.8 ou 9.9.9.9.
- Não há descriptografia HTTPS.
- A lista ampliada opcional é baixada de StevenBlack/hosts.

Limitações da V1:
- DNS UDP apenas. Consultas DNS por TCP/DoH/DoT podem escapar.
- Apps que entregam anúncios pelo mesmo domínio do conteúdo não podem ser filtrados com segurança apenas por DNS.
- Não é possível usar outra VPN comum ao mesmo tempo na maioria dos aparelhos Android.
