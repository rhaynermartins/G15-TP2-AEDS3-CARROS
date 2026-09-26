package service;

import dao.ArquivoSequencial;
import index.bplus.ArvoreBPlus;
import index.hash.HashEstendido;
import index.lista.ListaInvertida;
import model.Carro;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Coordena os índices com o formato do TP1. Uso por uma aplicação/escritor de cada vez.
 * O marcador persistente evita usar índices após uma operação interrompida.
 * Não há rollback dos dados: uma falha exige reconstruir a partir da base ativa.
 */
public class GerenciadorIndices {
    private final ArquivoSequencial dados;
    private final Path indice, marca, assinatura;
    private final IndicesSecundarios secundarios;

    public GerenciadorIndices(ArquivoSequencial dados, Path indice) {
        this.dados = dados;
        this.indice = indice;
        secundarios = new IndicesSecundarios(dados, indice.toAbsolutePath().getParent());
        marca = indice.resolveSibling(indice.getFileName() + ".pendente");
        assinatura = indice.resolveSibling(indice.getFileName() + ".meta");
        if (dados.getCaminho().toAbsolutePath().normalize().equals(indice.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Dados e índice devem usar arquivos distintos.");
        }
    }

    public boolean existe() { return Files.exists(indice); }
    public boolean ativo() { return existe() || Files.exists(marca) || secundarios.ativo(); }
    public boolean fase2Ativa() { return secundarios.ativo(); }

    public int ordem() throws IOException {
        try (ArvoreBPlus arvore = ArvoreBPlus.abrir(indice)) { return arvore.getOrdem(); }
    }

    public int ordemConfigurada() throws IOException {
        try { return ordem(); }
        catch (IOException original) {
            if (!Files.isRegularFile(marca)) throw original;
            try (DataInputStream in = new DataInputStream(Files.newInputStream(marca))) {
                int ordem = in.readInt();
                ArvoreBPlus.validarOrdem(ordem);
                return ordem;
            }
        }
    }

    private IOException inconsistente() {
        return new IOException("Índices inconsistentes. Reconstrua todos os índices.");
    }

    private String estadoDados() throws IOException {
        BasicFileAttributes a = Files.readAttributes(dados.getCaminho(), BasicFileAttributes.class);
        return dados.getCaminho().toRealPath() + "|" + a.fileKey() + "|" + a.size()
                + "|" + a.lastModifiedTime();
    }

    private void conferir() throws IOException {
        if (!existe() || Files.exists(marca) || !Files.isRegularFile(assinatura)) throw inconsistente();
        try (DataInputStream in = new DataInputStream(Files.newInputStream(assinatura))) {
            if (!in.readUTF().equals(estadoDados())) throw inconsistente();
        }
        if (secundarios.ativo()) secundarios.conferirArquivos();
    }

    private void marcar(int ordem) throws IOException {
        if (indice.getParent() != null) Files.createDirectories(indice.getParent());
        try (RandomAccessFile arquivo = new RandomAccessFile(marca.toFile(), "rw")) {
            arquivo.setLength(0);
            arquivo.writeInt(ordem);
            arquivo.getFD().sync();
        }
    }

    private static void substituir(Path fonte, Path destino) throws IOException {
        try {
            Files.move(fonte, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(fonte, destino, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void concluir() throws IOException {
        Path temp = Files.createTempFile(indice.toAbsolutePath().getParent(), "assinatura-", ".tmp");
        try {
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(temp))) {
                out.writeUTF(estadoDados());
            }
            try (RandomAccessFile f = new RandomAccessFile(temp.toFile(), "rw")) { f.getFD().sync(); }
            substituir(temp, assinatura);
            Files.deleteIfExists(marca);
        } finally { Files.deleteIfExists(temp); }
    }

    public void reconstruir(int ordem) throws IOException {
        ArvoreBPlus.validarOrdem(ordem);
        dados.garantirArquivo();
        String antes = estadoDados();
        marcar(ordem);
        Path temp = Files.createTempFile(indice.toAbsolutePath().getParent(), "bplus-", ".tmp");
        Files.delete(temp); // criar() exige destino novo; é exclusivamente o temporário desta operação.
        try {
            try (ArvoreBPlus arvore = ArvoreBPlus.criar(temp, ordem)) {
                dados.percorrerAtivos(arvore::inserir);
                arvore.validar(null);
            }
            if (secundarios.ativo()) secundarios.reconstruir();
            if (!antes.equals(estadoDados())) throw new IOException("Dados alterados durante reconstrução.");
            substituir(temp, indice);
            concluir();
        } finally { Files.deleteIfExists(temp); }
    }

    /** Ativação explícita: quantidade inicial recebida, nunca inferida de ultimoId/ativos.
     * Reconstruções seguintes reutilizam fase2.meta e não dependem do CSV.
     */
    public void reconstruirTodos(int ordem, long quantidadeInicial) throws IOException {
        ArvoreBPlus.validarOrdem(ordem);
        HashEstendido.capacidadePara(quantidadeInicial);
        marcar(ordem);
        secundarios.configurar(quantidadeInicial);
        reconstruir(ordem);
    }

    public long quantidadeInicial() throws IOException { return secundarios.quantidadeInicial(); }

    public void reconstruirTodos() throws IOException {
        reconstruirTodos(ordemConfigurada(), quantidadeInicial());
    }

    private void conferirFase2() throws IOException {
        conferir();
        if (!secundarios.ativo()) throw new IOException("Ative os índices da Fase 2 pela reconstrução completa.");
    }

    public Carro buscarHash(int id) throws IOException { conferirFase2(); return secundarios.buscarHash(id); }
    public List<Carro> buscarAno(int ano) throws IOException { conferirFase2(); return secundarios.buscar(ano, null); }
    public List<Carro> buscarCaracteristica(String termo) throws IOException {
        if (termo == null) throw new IllegalArgumentException("Informe uma característica.");
        conferirFase2(); return secundarios.buscar(null, termo);
    }
    public List<Carro> buscarCombinada(int ano, String termo) throws IOException {
        if (termo == null) throw new IllegalArgumentException("Informe uma característica.");
        conferirFase2(); return secundarios.buscar(ano, termo);
    }
    public String informacoesHash() throws IOException { conferirFase2(); return secundarios.informacoesHash(); }
    public String validarHash() throws IOException { conferirFase2(); return secundarios.validarHash(); }
    public String validarListaAno() throws IOException { conferirFase2(); return secundarios.validarLista(true); }
    public String validarListaCaracteristicas() throws IOException { conferirFase2(); return secundarios.validarLista(false); }

    String estadoConsistente() throws IOException { conferirFase2(); return estadoDados(); }

    long localizarPosicao(int id, boolean peloHash) throws IOException {
        conferirFase2();
        if (peloHash) {
            try (HashEstendido hash = secundarios.abrirHash()) { return hash.buscar(id); }
        }
        try (ArvoreBPlus arvore = ArvoreBPlus.abrir(indice)) { return arvore.buscar(id); }
    }

    Carro lerDireto(long posicao, int id) throws IOException {
        conferirFase2(); return dados.readAtPosition(posicao, id);
    }

    List<ListaInvertida.Posting> postings(Integer ano, String termo) throws IOException {
        conferirFase2(); return secundarios.postings(ano, termo);
    }

    Carro lerPosting(ListaInvertida.Posting posting, Integer ano, String termo) throws IOException {
        conferirFase2(); return secundarios.lerPosting(posting, ano, termo);
    }

    public Carro buscar(int id) throws IOException {
        conferir();
        try (ArvoreBPlus arvore = ArvoreBPlus.abrir(indice)) {
            long posicao = arvore.buscar(id);
            return posicao == -1 ? null : dados.readAtPosition(posicao, id);
        }
    }

    public int criar(Carro carro) throws IOException {
        return criarComPosicao(carro).id;
    }

    public ArquivoSequencial.InfoRegistro criarComPosicao(Carro carro) throws IOException {
        conferir();
        ArquivoSequencial.InfoRegistro registro;
        try (ArvoreBPlus arvore = ArvoreBPlus.abrir(indice)) {
            marcar(arvore.getOrdem());
            registro = dados.createComPosicao(carro);
            arvore.inserir(registro.id, registro.posicao);
            if (secundarios.ativo()) secundarios.inserir(carro, registro.posicao);
        }
        concluir();
        return registro;
    }

    public boolean atualizar(Carro carro) throws IOException {
        conferir();
        try (ArvoreBPlus arvore = ArvoreBPlus.abrir(indice)) {
            long anterior = arvore.buscar(carro.getId());
            if (anterior == -1) return false;
            atualizarNaPosicao(anterior, carro);
            return true;
        }
    }

    /** A posição já veio da estrutura escolhida; B+ participa apenas da manutenção. */
    long atualizarNaPosicao(long anterior, Carro carro) throws IOException {
        conferir();
        long nova;
        try (ArvoreBPlus arvore = ArvoreBPlus.abrir(indice)) {
            Carro antigo = dados.readAtPosition(anterior, carro.getId());
            marcar(arvore.getOrdem());
            nova = dados.updateAtPosition(anterior, carro);
            if (!arvore.atualizarPosicao(carro.getId(), nova)) throw inconsistente();
            if (secundarios.ativo()) secundarios.atualizar(antigo, carro, nova);
        }
        concluir();
        return nova;
    }

    public boolean excluir(int id) throws IOException {
        conferir();
        try (ArvoreBPlus arvore = ArvoreBPlus.abrir(indice)) {
            long posicao = arvore.buscar(id);
            if (posicao == -1) return false;
            excluirNaPosicao(posicao, id);
            return true;
        }
    }

    void excluirNaPosicao(long posicao, int id) throws IOException {
        conferir();
        try (ArvoreBPlus arvore = ArvoreBPlus.abrir(indice)) {
            Carro antigo = dados.readAtPosition(posicao, id);
            marcar(arvore.getOrdem());
            dados.deleteAtPosition(posicao, id);
            if (!arvore.remover(id)) throw inconsistente();
            if (secundarios.ativo()) secundarios.remover(antigo);
        }
        concluir();
    }

    @FunctionalInterface
    public interface Operacao<T> { T executar() throws IOException; }

    /** Preserva o CRUD sequencial/carga do menu e recompõe todos os índices ativos.
     * Também protege ordenações: o marcador é gravado ANTES de mudar os endereços.
     */
    public <T> T executarAlteracaoSequencial(Operacao<T> operacao) throws IOException {
        if (!ativo()) return operacao.executar();
        conferir();
        int ordem = ordem();
        marcar(ordem);
        T resultado = operacao.executar();
        reconstruir(ordem);
        return resultado;
    }

    public OrdenacaoExterna.ResultadoOrdenacao ordenar(Path temporarios, int caminhos,
                                                       int memoria, boolean selecao) throws IOException {
        return executarAlteracaoSequencial(() -> selecao
                ? new OrdenacaoExterna().ordenarComSelecaoPorSubstituicao(
                        dados.getCaminho(), temporarios, caminhos, memoria)
                : new OrdenacaoExterna().ordenar(dados.getCaminho(), temporarios, caminhos, memoria));
    }

    public String validar() throws IOException {
        conferir();
        try (ArvoreBPlus arvore = ArvoreBPlus.abrir(indice)) {
            String resultado = arvore.validar((id, pos) -> dados.readAtPosition(pos, id));
            long[] total = {0};
            dados.percorrerAtivos((id, pos) -> {
                if (arvore.buscar(id) != pos) throw new IOException("Entrada ausente/divergente para ID " + id);
                total[0]++;
            });
            if (total[0] != arvore.getQuantidade()) throw inconsistente();
            return resultado + "; dados ativos=" + total[0];
        }
    }

    public String informacoes() throws IOException {
        conferir();
        try (ArvoreBPlus arvore = ArvoreBPlus.abrir(indice)) {
            return "Ordem=" + arvore.getOrdem() + "; entradas=" + arvore.getQuantidade()
                    + "; página=" + arvore.getTamanhoPagina() + " bytes; arquivo=" + Files.size(indice) + " bytes";
        }
    }

    public String informacoesTodas() throws IOException {
        conferirFase2();
        try (ArvoreBPlus arvore = ArvoreBPlus.abrir(indice)) {
            return arvore.validar(null) + "; ordem=" + arvore.getOrdem() + "; arquivo=" + Files.size(indice)
                    + " bytes\n" + secundarios.informacoesHash() + "\n" + secundarios.informacoesListas();
        }
    }

    private String validarDados() throws IOException {
        if (!Files.isRegularFile(dados.getCaminho()) || Files.size(dados.getCaminho()) < 4) {
            throw new IOException("Cabeçalho de dados ausente/incompleto.");
        }
        int ultimo = dados.getUltimoId();
        if (ultimo < 0) throw new IOException("ultimoId negativo.");
        Set<Integer> ids = new HashSet<>();
        dados.percorrerAtivos((id, pos) -> {
            if (id <= 0 || id > ultimo || !ids.add(id)) throw new IOException("ID ativo inválido/duplicado: " + id);
        });
        return "Arquivo de dados: OK; registros ativos=" + ids.size() + "; ultimoId=" + ultimo;
    }

    /** Diagnóstico independente por estrutura; não reconstrói nem oculta divergências. */
    public String validarTodos() throws IOException {
        StringBuilder resultado = new StringBuilder();
        String[] nomes = {"Arquivo de dados", "B+", "Hash Estendido", "Lista Ano", "Lista Características"};
        List<Operacao<String>> validacoes = Arrays.asList(this::validarDados, this::validar,
                this::validarHash, this::validarListaAno, this::validarListaCaracteristicas);
        boolean consistente = true;
        for (int i = 0; i < validacoes.size(); i++) {
            try { resultado.append(validacoes.get(i).executar()); }
            catch (IOException | RuntimeException e) {
                consistente = false;
                resultado.append(nomes[i]).append(": ERRO — ").append(e.getMessage());
            }
            resultado.append('\n');
        }
        if (!consistente) {
            int ordem = 0;
            try { ordem = ordemConfigurada(); } catch (IOException | IllegalArgumentException ignorada) { /* Cabeçalho também pode estar danificado. */ }
            marcar(ordem);
            resultado.append("Índices inconsistentes. Reconstrua todos os índices.");
        } else resultado.append("Validação global: OK");
        return resultado.toString();
    }

    /** Medição simples da mesma sequência de consultas; não é benchmark científico. */
    public String compararBuscas(int[] ids, int repeticoes) throws IOException {
        if (ids.length == 0 || repeticoes < 1 || repeticoes > 1000) {
            throw new IllegalArgumentException("Informe IDs e de 1 a 1000 repetições.");
        }
        conferir();
        long sequencial = 0, indexado = 0, hashing = 0;
        boolean temHash = secundarios.ativo();
        try (ArvoreBPlus arvore = ArvoreBPlus.abrir(indice);
             HashEstendido hash = temHash ? secundarios.abrirHash() : null) {
            for (int r = 0; r < repeticoes; r++) {
                for (int id : ids) {
                    long inicio = System.nanoTime();
                    Carro a = dados.read(id);
                    sequencial += System.nanoTime() - inicio;
                    inicio = System.nanoTime();
                    long pos = arvore.buscar(id);
                    Carro b = pos == -1 ? null : dados.readAtPosition(pos, id);
                    indexado += System.nanoTime() - inicio;
                    if (a == null ? b != null : !a.equals(b)) throw inconsistente();
                    if (hash != null) {
                        inicio = System.nanoTime();
                        long posHash = hash.buscar(id);
                        Carro c = posHash == -1 ? null : dados.readAtPosition(posHash, id);
                        hashing += System.nanoTime() - inicio;
                        if (a == null ? c != null : !a.equals(c)) throw inconsistente();
                    }
                }
            }
        }
        return "IDs=" + Arrays.toString(ids) + "; consultas=" + (long) ids.length * repeticoes
                + "; sequencial_ns=" + sequencial + "; bplus_ns=" + indexado
                + (temHash ? "; hash_ns=" + hashing : "");
    }
}
