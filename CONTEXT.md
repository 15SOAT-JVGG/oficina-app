# Linguagem do domínio

## Cliente

- **Cliente ativo:** cliente que pode se autenticar pelo CPF. Desativar não apaga a ficha nem o
  histórico; só impede novos logins no canal do cliente.
- **Canal do cliente:** rotas `/v1/me` consumidas com o token emitido pela autenticação por CPF; o
  cliente só enxerga e decide sobre as próprias Ordens de Serviço.

## Ordem de Serviço

- **Acompanhamento:** leitura do andamento da Ordem de Serviço pelo cliente, autorizada apenas pelo
  link assinado que a oficina lhe envia por e-mail.
- **Cancelamento:** encerramento da Ordem de Serviço sem execução, sempre com um motivo registrado:
  recusa do orçamento pelo cliente, expiração do prazo de decisão ou falha do atendimento antes de
  a execução começar. Depois que a execução começa, uma falha não cancela a ordem: ela vai para
  revisão manual.
- **Cobrança:** pedido de pagamento do valor final, emitido ao cliente quando a Ordem de Serviço é
  concluída normalmente.
- **Decisão:** aprovação ou recusa do orçamento pelo cliente, autorizada por um link assinado de uso
  único ou pelo canal do cliente, e registrada uma só vez por orçamento.
- **Conclusão:** término da execução do serviço ou encerramento decorrente de cancelamento.
- **Entrega:** devolução do veículo ao cliente após a conclusão normal da Ordem de Serviço e a
  confirmação do pagamento da cobrança.
- **Revisão manual:** situação de uma Ordem de Serviço cujo atendimento falhou depois de iniciada a
  execução e que espera uma decisão da oficina, porque trabalho já feito não se desfaz.
- **Fila operacional:** conjunto das Ordens de Serviço ainda em atendimento, do estágio de trabalho
  mais avançado para o recém-recebido e, dentro de cada estágio, da mais antiga para a mais recente.
- **Reserva:** separação das peças do orçamento no estoque no momento em que a oficina passa a
  aguardar a decisão do cliente; a aprovação a mantém e a recusa a devolve.
- **Solicitação inicial:** serviços e peças informados na abertura da Ordem de Serviço, que nascem
  como o orçamento pendente da ordem, com os preços do catálogo copiados naquele instante.
