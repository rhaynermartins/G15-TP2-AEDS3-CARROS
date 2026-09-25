package dao;

import model.Carro;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * CRUD sequencial sobre arquivo binário.
 *
 * Estrutura física:
 * Cabeçalho: [int ultimoId]
 * Registro:  [byte lapide][int tamanho][byte[] dados]
 *
 * Convenção de lápide:
 * 0 = ativo
 * 1 = excluído
 */
public class ArquivoSequencial {
    public static final byte ATIVO = 0;
    public static final byte EXCLUIDO = 1;
    public static final int TAMANHO_CABECALHO = Integer.BYTES;

    private final Path caminho;

    public ArquivoSequencial(Path caminho) {
        this.caminho = caminho;
    }

    public Path getCaminho() { return caminho; }

    /** Cria o arquivo com cabeçalho 0 se ele ainda não existir. */
    public void garantirArquivo() throws IOException {
        if (caminho.getParent() != null) Files.createDirectories(caminho.getParent());
        if (!Files.exists(caminho) || Files.size(caminho) == 0) {
            try (RandomAccessFile raf = new RandomAccessFile(caminho.toFile(), "rw")) {
                raf.setLength(0);
                raf.writeInt(0);
            }
        }
    }

    public int getUltimoId() throws IOException {
        garantirArquivo();
        try (RandomAccessFile raf = new RandomAccessFile(caminho.toFile(), "r")) {
            return raf.readInt();
        }
    }

    public void setUltimoId(int ultimoId) throws IOException {
        garantirArquivo();
        try (RandomAccessFile raf = new RandomAccessFile(caminho.toFile(), "rw")) {
            raf.seek(0);
            raf.writeInt(ultimoId);
        }
    }

    /**
     * CREATE: nunca reaproveita IDs apagados. O novo ID é ultimoId + 1.
     */
    public int create(Carro objeto) throws IOException {
        return createComPosicao(objeto).id;
    }

    /** Retorna o endereço da lápide junto ao ID, sem uma busca sequencial posterior. */
    public InfoRegistro createComPosicao(Carro objeto) throws IOException {
        garantirArquivo();
        try (RandomAccessFile raf = new RandomAccessFile(caminho.toFile(), "rw")) {
            int ultimoId = raf.readInt();
            if (ultimoId == Integer.MAX_VALUE) throw new IOException("Limite de IDs atingido.");
            int novoId = ultimoId + 1;
            objeto.setId(novoId);
            if (objeto.getCodigo() == null || objeto.getCodigo().isBlank()) {
                objeto.setCodigo(Carro.codigoPorId(novoId));
            }

            byte[] dados = objeto.toByteArray();

            raf.seek(0);
            raf.writeInt(novoId);
            long posicao = raf.length();
            raf.seek(posicao);
            escreverRegistro(raf, ATIVO, dados);
            return new InfoRegistro(posicao, ATIVO, dados.length, novoId);
        }
    }

    /** A posição do índice aponta para a lápide, não para o início do objeto. */
    public Carro readAtPosition(long posicao, int idEsperado) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(caminho.toFile(), "r")) {
            return lerAtivoNaPosicao(raf, posicao, idEsperado);
        }
    }

    private static Carro lerAtivoNaPosicao(RandomAccessFile raf, long posicao,
                                           int idEsperado) throws IOException {
        if (posicao < TAMANHO_CABECALHO || posicao > raf.length() - 5) {
            throw new IOException("Posição indexada inválida; reconstrua o índice.");
        }
        raf.seek(posicao);
        RegistroFisico registro = lerRegistroFisico(raf);
        if (registro.lapide != ATIVO) {
            throw new IOException("Índice aponta para lápide; reconstrua o índice.");
        }
        Carro carro = desserializar(registro.dados);
        if (carro.getId() != idEsperado) {
            throw new IOException("ID diverge da posição indexada; reconstrua o índice.");
        }
        return carro;
    }

    /** Atualização direta: preserva o ID e retorna o endereço da versão ativa. */
    public long updateAtPosition(long posicao, Carro novo) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(caminho.toFile(), "rw")) {
            Carro anterior = lerAtivoNaPosicao(raf, posicao, novo.getId());
            if (novo.getCodigo() == null || novo.getCodigo().isBlank()) {
                novo.setCodigo(anterior.getCodigo());
            }
            byte[] bytes = novo.toByteArray();
            raf.seek(posicao + 1);
            int tamanho = raf.readInt();
            if (tamanho == bytes.length) {
                raf.write(bytes);
                return posicao;
            }
            long destino = raf.length();
            raf.seek(destino);
            escreverRegistro(raf, ATIVO, bytes);
            raf.seek(posicao);
            raf.writeByte(EXCLUIDO);
            return destino;
        }
    }

    public void deleteAtPosition(long posicao, int id) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(caminho.toFile(), "rw")) {
            lerAtivoNaPosicao(raf, posicao, id);
            raf.seek(posicao);
            raf.writeByte(EXCLUIDO);
        }
    }

    @FunctionalInterface
    public interface VisitanteRegistro {
        void visitar(int id, long posicao) throws IOException;
    }

    /** Reconstrução em fluxo: um registro por vez, sem materializar a base. */
    public void percorrerAtivos(VisitanteRegistro visitante) throws IOException {
        garantirArquivo();
        try (RandomAccessFile raf = new RandomAccessFile(caminho.toFile(), "r")) {
            raf.seek(TAMANHO_CABECALHO);
            while (raf.getFilePointer() < raf.length()) {
                long posicao = raf.getFilePointer();
                RegistroFisico registro = lerRegistroFisico(raf);
                if (registro.lapide != ATIVO && registro.lapide != EXCLUIDO) {
                    throw new IOException("Lápide inválida na posição " + posicao);
                }
                if (registro.lapide == ATIVO) {
                    visitante.visitar(desserializar(registro.dados).getId(), posicao);
                }
            }
        }
    }

    /** READ sequencial ignorando registros com lápide. */
    public Carro read(int id) throws IOException {
        garantirArquivo();
        try (RandomAccessFile raf = new RandomAccessFile(caminho.toFile(), "r")) {
            raf.seek(TAMANHO_CABECALHO);
            while (raf.getFilePointer() < raf.length()) {
                RegistroFisico reg = lerRegistroFisico(raf);
                if (reg == null) break;
                if (reg.lapide == ATIVO) {
                    Carro p = desserializar(reg.dados);
                    if (p.getId() == id) return p;
                }
            }
        }
        return null;
    }

    /**
     * UPDATE obrigatório em dois casos:
     * - mesmo tamanho: sobrescreve os bytes no mesmo endereço;
     * - tamanho diferente: marca o antigo como excluído e anexa a nova versão no fim,
     *   preservando exatamente o mesmo ID.
     */
    public boolean update(Carro novoObjeto) throws IOException {
        garantirArquivo();
        try (RandomAccessFile raf = new RandomAccessFile(caminho.toFile(), "rw")) {
            raf.seek(TAMANHO_CABECALHO);
            while (raf.getFilePointer() < raf.length()) {
                long posLapide = raf.getFilePointer();
                byte lapide;
                int tamanho;
                try {
                    lapide = raf.readByte();
                    tamanho = raf.readInt();
                } catch (EOFException e) {
                    return false;
                }
                validarTamanhoRegistro(raf, tamanho);
                long posDados = raf.getFilePointer();
                byte[] dadosAntigos = new byte[tamanho];
                raf.readFully(dadosAntigos);

                if (lapide != ATIVO) continue;

                Carro atual = desserializar(dadosAntigos);
                if (atual.getId() != novoObjeto.getId()) continue;

                // O ID não pode ser alterado durante a atualização.
                novoObjeto.setId(atual.getId());
                if (novoObjeto.getCodigo() == null || novoObjeto.getCodigo().isBlank()) {
                    novoObjeto.setCodigo(atual.getCodigo());
                }
                byte[] novosDados = novoObjeto.toByteArray();

                if (novosDados.length == tamanho) {
                    raf.seek(posDados);
                    raf.write(novosDados);
                } else {
                    raf.seek(posLapide);
                    raf.writeByte(EXCLUIDO);
                    raf.seek(raf.length());
                    escreverRegistro(raf, ATIVO, novosDados);
                }
                return true;
            }
        }
        return false;
    }

    /** DELETE lógico: altera somente a lápide. */
    public boolean delete(int id) throws IOException {
        garantirArquivo();
        try (RandomAccessFile raf = new RandomAccessFile(caminho.toFile(), "rw")) {
            raf.seek(TAMANHO_CABECALHO);
            while (raf.getFilePointer() < raf.length()) {
                long posLapide = raf.getFilePointer();
                byte lapide;
                int tamanho;
                try {
                    lapide = raf.readByte();
                    tamanho = raf.readInt();
                } catch (EOFException e) {
                    return false;
                }
                validarTamanhoRegistro(raf, tamanho);
                byte[] dados = new byte[tamanho];
                raf.readFully(dados);

                Carro p = desserializar(dados);
                if (p.getId() == id) {
                    // Um update com tamanho diferente deixa uma versão antiga apagada
                    // antes da nova versão ativa. Portanto, não podemos parar na primeira
                    // ocorrência do ID se ela já estiver com lápide.
                    if (lapide == EXCLUIDO) continue;
                    raf.seek(posLapide);
                    raf.writeByte(EXCLUIDO);
                    return true;
                }
            }
        }
        return false;
    }

    /** Percorre o arquivo todo e devolve somente registros ativos. */
    public List<Carro> listarAtivos() throws IOException {
        garantirArquivo();
        List<Carro> lista = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(caminho.toFile(), "r")) {
            raf.seek(TAMANHO_CABECALHO);
            while (raf.getFilePointer() < raf.length()) {
                RegistroFisico reg = lerRegistroFisico(raf);
                if (reg == null) break;
                if (reg.lapide == ATIVO) lista.add(desserializar(reg.dados));
            }
        }
        return lista;
    }

    public int contarAtivos() throws IOException {
        return listarAtivos().size();
    }

    public int contarLapides() throws IOException {
        garantirArquivo();
        int total = 0;
        try (RandomAccessFile raf = new RandomAccessFile(caminho.toFile(), "r")) {
            raf.seek(TAMANHO_CABECALHO);
            while (raf.getFilePointer() < raf.length()) {
                RegistroFisico reg = lerRegistroFisico(raf);
                if (reg == null) break;
                if (reg.lapide == EXCLUIDO) total++;
            }
        }
        return total;
    }

    /**
     * Inspeção física para demonstrar endereço, lápide, tamanho e ID de cada versão.
     */
    public List<InfoRegistro> inspecionar() throws IOException {
        garantirArquivo();
        List<InfoRegistro> infos = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(caminho.toFile(), "r")) {
            raf.seek(TAMANHO_CABECALHO);
            while (raf.getFilePointer() < raf.length()) {
                long pos = raf.getFilePointer();
                RegistroFisico reg = lerRegistroFisico(raf);
                if (reg == null) break;
                int id = -1;
                try { id = desserializar(reg.dados).getId(); } catch (IOException ignored) {}
                infos.add(new InfoRegistro(pos, reg.lapide, reg.dados.length, id));
            }
        }
        return infos;
    }

    public InfoRegistro localizarFisicamenteAtivo(int id) throws IOException {
        for (InfoRegistro info : inspecionar()) {
            if (info.id == id && info.lapide == ATIVO) return info;
        }
        return null;
    }

    public void imprimirEstruturaFisica(PrintStream out) throws IOException {
        out.println("Cabeçalho: últimoId = " + getUltimoId());
        for (InfoRegistro info : inspecionar()) {
            out.println();
            out.println("Posição: " + info.posicao);
            out.println("Lápide: " + info.lapide + (info.lapide == ATIVO ? " (ativo)" : " (excluído)"));
            out.println("Tamanho: " + info.tamanho);
            out.println("ID: " + info.id);
        }
    }

    /** Sobrescreve completamente o arquivo com cabeçalho informado. Usado pela importação. */
    public void reinicializar(int ultimoId) throws IOException {
        if (caminho.getParent() != null) Files.createDirectories(caminho.getParent());
        try (RandomAccessFile raf = new RandomAccessFile(caminho.toFile(), "rw")) {
            raf.setLength(0);
            raf.writeInt(ultimoId);
        }
    }

    /** Anexa registro com ID já definido, usado exclusivamente durante importação/compactação. */
    public void appendComId(Carro p) throws IOException {
        garantirArquivo();
        byte[] dados = p.toByteArray();
        try (RandomAccessFile raf = new RandomAccessFile(caminho.toFile(), "rw")) {
            raf.seek(raf.length());
            escreverRegistro(raf, ATIVO, dados);
        }
    }

    private static void escreverRegistro(RandomAccessFile raf, byte lapide, byte[] dados) throws IOException {
        raf.writeByte(lapide);
        raf.writeInt(dados.length);
        raf.write(dados);
    }

    private static RegistroFisico lerRegistroFisico(RandomAccessFile raf) throws IOException {
        if (raf.getFilePointer() >= raf.length()) return null;
        byte lapide;
        int tamanho;
        try {
            lapide = raf.readByte();
            tamanho = raf.readInt();
        } catch (EOFException e) {
            throw new EOFException("EOF inesperado ao ler cabeçalho de registro na posição " + raf.getFilePointer());
        }
        validarTamanhoRegistro(raf, tamanho);
        byte[] dados = new byte[tamanho];
        raf.readFully(dados);
        return new RegistroFisico(lapide, dados);
    }

    private static void validarTamanhoRegistro(RandomAccessFile raf, int tamanho) throws IOException {
        long restantes = raf.length() - raf.getFilePointer();
        if (tamanho < 0 || tamanho > restantes) {
            throw new EOFException("Registro corrompido ou EOF inesperado. Tamanho=" + tamanho + ", bytes restantes=" + restantes);
        }
    }

    private static Carro desserializar(byte[] dados) throws IOException {
        Carro p = new Carro();
        p.fromByteArray(dados);
        return p;
    }

    private static class RegistroFisico {
        final byte lapide;
        final byte[] dados;
        RegistroFisico(byte lapide, byte[] dados) {
            this.lapide = lapide;
            this.dados = dados;
        }
    }

    public static class InfoRegistro {
        public final long posicao;
        public final byte lapide;
        public final int tamanho;
        public final int id;

        public InfoRegistro(long posicao, byte lapide, int tamanho, int id) {
            this.posicao = posicao;
            this.lapide = lapide;
            this.tamanho = tamanho;
            this.id = id;
        }

        @Override
        public String toString() {
            return "InfoRegistro{posição=" + posicao + ", lápide=" + lapide + ", tamanho=" + tamanho + ", id=" + id + '}';
        }
    }
}
