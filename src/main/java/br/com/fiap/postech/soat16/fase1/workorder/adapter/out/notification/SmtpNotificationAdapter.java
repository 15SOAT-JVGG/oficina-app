package br.com.fiap.postech.soat16.fase1.workorder.adapter.out.notification;

import java.time.format.DateTimeFormatter;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import br.com.fiap.postech.soat16.fase1.workorder.application.port.out.EstimateDecisionInvitation;
import br.com.fiap.postech.soat16.fase1.workorder.application.port.out.WorkOrderNotificationPort;
import br.com.fiap.postech.soat16.fase1.workorder.application.port.out.WorkOrderTrackingInvitation;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.Estimate;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrder;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.logging.Log;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.smallrye.mutiny.Uni;

/**
 * Entrega por SMTP as mensagens que a oficina deve ao cliente. É este adaptador que conhece o
 * endereço público da API e monta a URL de cada link — de decisão ou de acompanhamento — a partir
 * do token assinado.
 *
 * <p>Uma OS sem e-mail de cliente cadastrado não interrompe o atendimento: a falta é registrada em
 * log e o fluxo segue, porque a reserva de estoque e a mudança de status já foram decididas.
 */
@ApplicationScoped
public class SmtpNotificationAdapter implements WorkOrderNotificationPort {

    private static final String DECISION_PATH = "/v1/public/work-orders/estimate-decisions/";
    private static final String TRACKING_PATH = "/v1/public/work-orders/tracking/";
    private static final DateTimeFormatter DEADLINE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm");

    private final ReactiveMailer mailer;
    private final Counter successes;
    private final Counter failures;
    private final String publicBaseUrl;

    public SmtpNotificationAdapter(
            ReactiveMailer mailer,
            MeterRegistry registry,
            @ConfigProperty(name = "app.public-base-url", defaultValue = "http://localhost:8080")
            String publicBaseUrl) {
        this.mailer = mailer;
        successes = registry.counter("workshop.integration.calls", "integration", "smtp", "outcome", "success");
        failures = registry.counter("workshop.integration.calls", "integration", "smtp", "outcome", "failure");
        this.publicBaseUrl = publicBaseUrl;
    }

    @Override
    public Uni<Void> notifyEstimateAwaitingDecision(WorkOrder order, Estimate estimate,
            EstimateDecisionInvitation invitation) {
        String recipient = customerEmail(order);
        if (recipient == null) {
            return skip(order, "orcamento aguardando decisao");
        }
        String body = """
                Ola, %s!

                O orcamento da sua ordem de servico %s esta pronto: R$ %s.

                Aprovar o servico:  %s
                Recusar o servico:  %s

                Os links valem para uma unica resposta e expiram em %s.
                """.formatted(
                        customerName(order),
                        order.getId(),
                        estimate.getTotalAmount(),
                        decisionLink(invitation.approveToken()),
                        decisionLink(invitation.rejectToken()),
                        invitation.expiresAt().format(DEADLINE_FORMAT));
        return deliver(order, "orcamento aguardando decisao", Mail.withText(recipient,
                "Orcamento da ordem de servico " + order.getId(), body));
    }

    @Override
    public Uni<Void> notifyWorkOrderProgress(WorkOrder order, WorkOrderTrackingInvitation invitation) {
        String recipient = customerEmail(order);
        if (recipient == null) {
            return skip(order, "andamento da ordem de servico");
        }
        String body = """
                Ola, %s!

                %s

                Acompanhe a sua ordem de servico %s por este link, valido ate %s:
                %s
                """.formatted(
                        customerName(order),
                        stage(order),
                        order.getId(),
                        invitation.expiresAt().format(DEADLINE_FORMAT),
                        trackingLink(invitation.trackingToken()));
        return deliver(order, "andamento da ordem de servico", Mail.withText(recipient,
                "Andamento da ordem de servico " + order.getId(), body));
    }

    /**
     * Entrega a mensagem sem deixar que o servidor de e-mail reprove a operação de negócio. A
     * reserva de estoque e a mudança de status já foram decididas quando a notificação sai; um
     * SMTP fora do ar, com a caixa cheia ou limitando a taxa de envio não pode desfazê-las, e
     * propagar essa falha transformaria indisponibilidade de e-mail em erro de atendimento.
     *
     * <p>É o mesmo critério aplicado à ordem sem e-mail de cliente: registra-se a falta e o fluxo
     * segue. O log é o que permite reenviar depois.
     */
    private Uni<Void> deliver(WorkOrder order, String subject, Mail mail) {
        return mailer.send(mail)
                .invoke(() -> successes.increment())
                .onFailure().recoverWithItem(failure -> {
                    failures.increment();
                    Log.warnf("Notificacao nao entregue. Operacao=%s Tipo=%s", subject, failure.getClass().getSimpleName());
                    return null;
                });
    }

    /**
     * O estágio é dito em linguagem de cliente, e não pelo nome do status: é ele quem vai ler.
     */
    private String stage(WorkOrder order) {
        return switch (order.getStatus()) {
            case RECEIVED -> "Recebemos o seu veiculo e abrimos o atendimento.";
            case DIAGNOSIS -> "Estamos diagnosticando o seu veiculo.";
            case WAITING_APPROVAL -> "O orcamento foi enviado e aguarda a sua resposta.";
            case IN_PROGRESS -> "O orcamento foi aprovado e o servico esta em execucao.";
            case COMPLETED -> order.wasCancelled()
                    ? "O orcamento foi recusado, e com isso encerramos o atendimento."
                    : "O servico foi concluido e o veiculo esta disponivel para retirada.";
            case DELIVERED -> "O veiculo foi entregue. Obrigado pela confianca!";
        };
    }

    private Uni<Void> skip(WorkOrder order, String subject) {
        Log.warnf("Notificacao nao enviada: OS %s nao tem e-mail de cliente. Assunto=%s",
                order.getId(), subject);
        return Uni.createFrom().voidItem();
    }

    private String decisionLink(String signedToken) {
        return publicBaseUrl + DECISION_PATH + signedToken;
    }

    private String trackingLink(String signedToken) {
        return publicBaseUrl + TRACKING_PATH + signedToken;
    }

    private String customerEmail(WorkOrder order) {
        return order.getCustomer() != null ? order.getCustomer().getEmail() : null;
    }

    private String customerName(WorkOrder order) {
        return order.getCustomer() != null ? order.getCustomer().getFirstName() : "cliente";
    }
}
