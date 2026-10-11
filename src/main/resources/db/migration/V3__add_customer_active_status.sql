-- Clientes já cadastrados nascem ativos: ninguém perde o acesso pelo CPF com a chegada do status.
ALTER TABLE IF EXISTS oficina_mecanica.customer
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;
