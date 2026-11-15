package br.com.fiap.postech.soat16.fase1.workorder.application.result;

/** A abertura retorna a OS imediatamente; o orçamento inicial ainda não existe localmente. */
public record OpenWorkOrderResult(WorkOrderResult workOrder) { }
