# Linguagem do domínio

## Cliente

- **Cliente ativo:** cliente que pode se autenticar pelo CPF. Desativar não apaga a ficha nem o
  histórico; só impede novos logins no canal do cliente.
- **Canal do cliente:** rotas `/v1/me` consumidas com o token emitido pela autenticação por CPF; o
  cliente só enxerga e decide sobre as próprias Ordens de Serviço.

## Ordem de Serviço

- **Acompanhamento:** leitura do andamento da Ordem de Serviço pelo cliente, autorizada apenas pelo
  link assinado que a oficina lhe envia por e-mail.
- **Cancelamento:** encerramento provocado exclusivamente pela recusa do orçamento pelo cliente.
- **Decisão:** aprovação ou recusa do orçamento pelo cliente, autorizada por um link assinado de uso
  único ou pelo canal do cliente, e registrada uma só vez por orçamento.
- **Conclusão:** término da execução do serviço ou encerramento decorrente de cancelamento.
- **Entrega:** devolução do veículo ao cliente após a conclusão normal da Ordem de Serviço.
- **Fila operacional:** conjunto das Ordens de Serviço ainda em atendimento, do estágio de trabalho
  mais avançado para o recém-recebido e, dentro de cada estágio, da mais antiga para a mais recente.
- **Reserva:** separação das peças do orçamento no estoque no momento em que a oficina passa a
  aguardar a decisão do cliente; a aprovação a mantém e a recusa a devolve.
- **Solicitação inicial:** serviços e peças informados na abertura da Ordem de Serviço, que nascem
  como o orçamento pendente da ordem, com os preços do catálogo copiados naquele instante.
