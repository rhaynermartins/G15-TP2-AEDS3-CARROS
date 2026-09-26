import dao.ArquivoSequencial;
import model.Carro;
import service.Importador;
import service.OrdenacaoExterna;
import service.GerenciadorIndices;
import service.CrudIndexado;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

public class Main {
    private static final Path CSV = Paths.get("data", "base.csv");
    private static final Path DB = Paths.get("data", "dados.db");
    private static final Path TEMP = Paths.get("temp");

    private final Scanner scanner = new Scanner(System.in);
    private final ArquivoSequencial arquivo = new ArquivoSequencial(DB);
    private final GerenciadorIndices indices = new GerenciadorIndices(
            arquivo, Paths.get("data", "index", "arvore_bplus.idx"));
    private final CrudIndexado crud = new CrudIndexado(indices);

    public static void main(String[] args) {
        new Main().executar();
    }

    private void executar() {
        try {
            arquivo.garantirArquivo();
        } catch (IOException e) {
            System.out.println("Não foi possível preparar o arquivo de dados: " + e.getMessage());
            return;
        }

        int opcao;
        do {
            imprimirMenu();
            opcao = lerInt("Escolha: ", -1);
            try {
                switch (opcao) {
                    case 1: carregarBase(); break;
                    case 2: criar(); break;
                    case 3: ler(); break;
                    case 4: atualizar(); break;
                    case 5: excluir(); break;
                    case 6: ordenar(); break;
                    case 7: listar(); break;
                    case 8: arquivo.imprimirEstruturaFisica(System.out); break;
                    case 9: menuIndices(); break;
                    case 0: System.out.println("Encerrando TP1."); break;
                    default: System.out.println("Opção inválida.");
                }
            } catch (Exception e) {
                System.out.println("Operação não concluída: " + e.getMessage());
            }
            System.out.println();
        } while (opcao != 0);
    }

    private void imprimirMenu() {
        System.out.println("========== TP1 AEDS III - GRUPO 15 ==========");
        System.out.println("Tema: Carros");
        System.out.println("Integrantes: Gabriel Benicio Fonseca e Rhayner Martins");
        System.out.println("1 - Carregar base de dados");
        System.out.println("2 - Criar registro");
        System.out.println("3 - Ler registro");
        System.out.println("4 - Atualizar registro");
        System.out.println("5 - Excluir registro");
        System.out.println("6 - Ordenação externa");
        System.out.println("7 - Listar registros ativos");
        System.out.println("8 - Visualizar estrutura física do arquivo");
        System.out.println("9 - TP2 — CRUD indexado");
        System.out.println("0 - Sair");
    }

    private void carregarBase() throws IOException {
        if (!Files.exists(CSV)) {
            System.out.println("Base CSV não encontrada em " + CSV);
            return;
        }

        if (Files.exists(DB) && Files.size(DB) > Integer.BYTES && arquivo.contarAtivos() > 0) {
            System.out.print("A carga sobrescreverá os dados atuais. Digite SIM para continuar: ");
            if (!scanner.nextLine().trim().equalsIgnoreCase("SIM")) {
                System.out.println("Carga cancelada.");
                return;
            }
        }

        Importador.ResultadoImportacao r = indices.executarAlteracaoSequencial(
                () -> new Importador().carregarBase(CSV, DB));
        System.out.println("Carga concluída. " + r);
    }

    private void criar() throws IOException {
        Carro p = lerNovoCarro();
        int id = indices.executarAlteracaoSequencial(() -> arquivo.create(p));
        System.out.println("Registro criado com ID " + id + " e código " + p.getCodigo());
    }

    private Carro lerNovoCarro() {
        String nome = lerStringObrigatoria("Modelo/nome do carro: ");
        List<String> caracteristicas = lerLista("Características separadas por | (pode deixar vazio): ");
        int ano = lerAno("Ano: ");
        LocalDate data = lerData("Data de registro (AAAA-MM-DD): ");

        return new Carro(0, "", nome, data, caracteristicas, ano);
    }

    private void ler() throws IOException {
        int id = lerInt("ID: ", -1);
        Carro p = arquivo.read(id);
        System.out.println(p == null ? "Registro não encontrado." : p);
    }

    private void atualizar() throws IOException {
        int id = lerInt("ID a atualizar: ", -1);
        Carro atual = arquivo.read(id);
        if (atual == null) {
            System.out.println("Registro não encontrado.");
            return;
        }

        Carro novo = editarCarro(atual);
        boolean ok = indices.executarAlteracaoSequencial(() -> arquivo.update(novo));
        System.out.println(ok ? "Registro atualizado." : "Registro não encontrado.");
    }

    private Carro editarCarro(Carro atual) {
        System.out.println("Atual: " + atual);
        System.out.println("Pressione ENTER para manter o valor atual.");

        String nome = lerOpcional("Modelo/nome [" + atual.getNome() + "]: ", atual.getNome());
        String caracteristicasTexto = lerOpcional("Características separadas por | [" + String.join("|", atual.getCaracteristicas()) + "]: ",
                String.join("|", atual.getCaracteristicas()));
        List<String> caracteristicas = parseLista(caracteristicasTexto);
        int ano = lerAnoOpcional("Ano [" + atual.getAno() + "]: ", atual.getAno());
        LocalDate data = lerDataOpcional("Data [" + atual.getDataRegistro() + "]: ", atual.getDataRegistro());

        return new Carro(atual.getId(), atual.getCodigo(), nome, data, caracteristicas, ano);
    }

    private void excluir() throws IOException {
        int id = lerInt("ID a excluir: ", -1);
        boolean ok = indices.executarAlteracaoSequencial(() -> arquivo.delete(id));
        System.out.println(ok ? "Registro marcado com lápide de exclusão." :
                "Registro não encontrado ou já estava apagado.");
    }

    private void ordenar() throws IOException {
        System.out.println("========== ORDENAÇÃO EXTERNA ==========");
        System.out.println("1 - Intercalação Balanceada Comum");
        System.out.println("2 - Intercalação Balanceada com Seleção por Substituição");
        System.out.println("0 - Voltar");
        int metodo = lerInt("Escolha o método: ", -1);
        if (metodo == 0) {
            System.out.println("Retornando ao menu principal.");
            return;
        }
        if (metodo != 1 && metodo != 2) {
            System.out.println("Método de ordenação inválido.");
            return;
        }

        int caminhos = lerInt("Número de caminhos (>= 2): ", -1);
        String mensagemMemoria = metodo == 1
                ? "Máximo de registros em memória (>= 1): "
                : "Tamanho da memória da seleção (>= 1): ";
        int memoria = lerInt(mensagemMemoria, -1);

        OrdenacaoExterna.ResultadoOrdenacao r = indices.ordenar(TEMP, caminhos, memoria, metodo == 2);
        System.out.println("Ordenação e compactação concluídas: " + r);
        System.out.println("O arquivo data/dados.db agora é a versão ordenada usada pelo CRUD.");
    }

    private void listar() throws IOException {
        List<Carro> lista = arquivo.listarAtivos();
        if (lista.isEmpty()) {
            System.out.println("Nenhum registro ativo.");
            return;
        }
        for (Carro p : lista) System.out.println(p);
        System.out.println("Total ativo: " + lista.size());
    }

    private void menuIndices() throws IOException {
        int opcao;
        do {
            System.out.println("========== TP2 — CRUD INDEXADO ==========");
            System.out.println("1 - Create");
            System.out.println("2 - Read");
            System.out.println("3 - Update");
            System.out.println("4 - Delete");
            System.out.println("5 - Consultas por listas invertidas");
            System.out.println("6 - Criar/reconstruir todos os índices");
            System.out.println("7 - Validar todos os índices");
            System.out.println("8 - Informações dos índices");
            System.out.println("9 - Comparar buscas");
            System.out.println("0 - Voltar");
            opcao = lerInt("Escolha: ", -1);
            try { switch (opcao) {
                case 1:
                    System.out.println("Create não requer índice de busca. Todos os índices serão atualizados.");
                    ArquivoSequencial.InfoRegistro criado = crud.criar(lerNovoCarro());
                    System.out.println("ID criado=" + criado.id + "; posição=" + criado.posicao);
                    confirmarIndices();
                    break;
                case 2:
                    lerIndexado();
                    break;
                case 3:
                    CrudIndexado.RegistroLocalizado alterar = selecionarRegistro();
                    if (alterar != null) {
                        long nova = crud.atualizar(alterar, editarCarro(alterar.getCarro()));
                        System.out.println("Registro atualizado. Posição anterior=" + alterar.getPosicao() + "; nova posição=" + nova);
                        confirmarIndices();
                    }
                    break;
                case 4:
                    CrudIndexado.RegistroLocalizado excluir = selecionarRegistro();
                    if (excluir != null) {
                        System.out.print("Excluir ID " + excluir.getId() + "? Digite SIM: ");
                        if (scanner.nextLine().trim().equalsIgnoreCase("SIM")) {
                            crud.excluir(excluir);
                            System.out.println("Registro marcado com lápide de exclusão.");
                            confirmarIndices();
                        } else System.out.println("Exclusão cancelada.");
                    }
                    break;
                case 5:
                    mostrarConsulta(consultarLista());
                    break;
                case 6:
                    reconstruirIndices();
                    break;
                case 7: System.out.println(indices.validarTodos()); break;
                case 8: System.out.println(indices.informacoesTodas()); break;
                case 9:
                    System.out.println("Medição local simples, sujeita a cache e ambiente.");
                    System.out.print("IDs separados por vírgula (ENTER: 1,2,50000,99999,100000,100001): ");
                    String texto = scanner.nextLine().trim();
                    if (texto.isEmpty()) texto = "1,2,50000,99999,100000,100001";
                    int[] ids = Arrays.stream(texto.split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray();
                    System.out.println(indices.compararBuscas(ids, lerInt("Repetições (1 a 1000; ENTER: 3): ", 3)));
                    break;
                case 0: break;
                default: System.out.println("Opção inválida.");
            } } catch (IOException | IllegalArgumentException e) {
                System.out.println("Operação não concluída: " + e.getMessage());
                if (e instanceof IOException) System.out.println("Para reconstruir os índices, utilize a opção 6.");
            }
        } while (opcao != 0);
    }

    private void confirmarIndices() {
        System.out.println("Índices atualizados: B+, Hash Estendido, Lista Ano e Lista Características.");
    }

    private void reconstruirIndices() throws IOException {
        if (indices.fase2Ativa()) {
            System.out.println("Preservando ordem B+=" + indices.ordemConfigurada() + "; população inicial=" + indices.quantidadeInicial());
            indices.reconstruirTodos();
        } else {
            int ordem = indices.ativo() ? indices.ordemConfigurada() : lerInt("Ordem da B+ (ENTER: 32): ", 32);
            long inicial = lerInt("População INICIAL (base oficial: 100000; ENTER: 100000): ", 100000);
            indices.reconstruirTodos(ordem, inicial);
        }
        System.out.println("Quatro índices reconstruídos a partir de dados.db. " + indices.informacoesHash());
    }

    private int escolherMetodo() {
        System.out.println("Localizar registro utilizando: 1 - Árvore B+ | 2 - Hash Estendido | 3 - Lista Invertida | 0 - Voltar");
        int metodo = lerInt("Método: ", -1);
        if (metodo < 0 || metodo > 3) throw new IllegalArgumentException("Método inválido.");
        return metodo;
    }

    private CrudIndexado.RegistroLocalizado localizarPorId(int metodo) throws IOException {
        CrudIndexado.RegistroLocalizado registro = crud.localizar(metodo == 1 ? CrudIndexado.Metodo.BPLUS : CrudIndexado.Metodo.HASH,
                lerInt("ID: ", -1));
        if (registro == null) System.out.println("Registro não encontrado.");
        else System.out.println("Localizado por " + (metodo == 1 ? "Árvore B+" : "Hash Estendido") + "; posição=" + registro.getPosicao());
        return registro;
    }

    private void lerIndexado() throws IOException {
        int metodo = escolherMetodo();
        if (metodo == 0) return;
        if (metodo == 3) mostrarConsulta(consultarLista());
        else {
            CrudIndexado.RegistroLocalizado registro = localizarPorId(metodo);
            if (registro != null) System.out.println(registro.getCarro());
        }
    }

    private CrudIndexado.RegistroLocalizado selecionarRegistro() throws IOException {
        int metodo = escolherMetodo();
        if (metodo == 0) return null;
        if (metodo != 3) return localizarPorId(metodo);
        CrudIndexado.Consulta consulta = consultarLista();
        mostrarConsulta(consulta);
        if (consulta == null || consulta.total() == 0) return null;
        CrudIndexado.RegistroLocalizado registro = consulta.selecionar(lerInt("ID dentre os resultados: ", -1));
        System.out.println("Localizado por Lista Invertida; posição=" + registro.getPosicao());
        return registro;
    }

    private CrudIndexado.Consulta consultarLista() throws IOException {
        System.out.println("Lista Invertida: 1 - Ano | 2 - Característica | 3 - Ano + Característica | 0 - Voltar");
        int filtro = lerInt("Filtro: ", -1);
        if (filtro == 0) return null;
        if (filtro < 1 || filtro > 3) throw new IllegalArgumentException("Filtro inválido.");
        Integer ano = filtro == 2 ? null : lerAno("Ano: ");
        String termo = filtro == 1 ? null : lerStringObrigatoria("Característica: ");
        return crud.consultar(ano, termo);
    }

    private void mostrarConsulta(CrudIndexado.Consulta consulta) throws IOException {
        if (consulta == null) return;
        System.out.println("Lista Invertida — total encontrado: " + consulta.total() + "; ordem crescente de ID.");
        for (int inicio = 0; inicio < consulta.total(); inicio += CrudIndexado.TAMANHO_PAGINA) {
            for (Carro c : consulta.pagina(inicio)) System.out.println("ID=" + c.getId() + " | ano=" + c.getAno() + " | " + c.getNome());
            if (inicio + CrudIndexado.TAMANHO_PAGINA >= consulta.total()) break;
            System.out.print("Mostrar mais 20? (s/N): ");
            if (!scanner.nextLine().trim().equalsIgnoreCase("s")) break;
        }
    }

    private int lerInt(String msg, int padrao) {
        while (true) {
            System.out.print(msg);
            String s = scanner.nextLine().trim();
            if (s.isEmpty() && padrao != -1) return padrao;
            try { return Integer.parseInt(s); }
            catch (NumberFormatException e) { System.out.println("Digite um número inteiro válido."); }
        }
    }

    private int lerAno(String msg) {
        while (true) {
            int ano = lerInt(msg, -1);
            if (ano > 0) return ano;
            System.out.println("Ano inválido. Digite um inteiro maior que zero.");
        }
    }

    private int lerAnoOpcional(String msg, int atual) {
        while (true) {
            System.out.print(msg);
            String s = scanner.nextLine().trim();
            if (s.isEmpty()) return atual;
            try {
                int ano = Integer.parseInt(s);
                if (ano > 0) return ano;
            } catch (NumberFormatException ignored) {
                // A mensagem de validação é a mesma para qualquer ano inválido.
            }
            System.out.println("Ano inválido. Digite um inteiro maior que zero.");
        }
    }

    private String lerStringObrigatoria(String msg) {
        while (true) {
            System.out.print(msg);
            String s = scanner.nextLine().trim();
            if (!s.isEmpty()) return s;
            System.out.println("O valor não pode ser vazio.");
        }
    }

    private String lerOpcional(String msg, String atual) {
        System.out.print(msg);
        String s = scanner.nextLine().trim();
        return s.isEmpty() ? atual : s;
    }

    private List<String> lerLista(String msg) {
        System.out.print(msg);
        return parseLista(scanner.nextLine());
    }

    private List<String> parseLista(String texto) {
        List<String> lista = new ArrayList<>();
        if (texto == null || texto.isBlank()) return lista;
        Arrays.stream(texto.split("\\|"))
                .map(String::trim).filter(s -> !s.isEmpty()).forEach(lista::add);
        return lista;
    }

    private LocalDate lerData(String msg) {
        while (true) {
            System.out.print(msg);
            try { return LocalDate.parse(scanner.nextLine().trim()); }
            catch (DateTimeParseException e) { System.out.println("Data inválida. Use AAAA-MM-DD."); }
        }
    }

    private LocalDate lerDataOpcional(String msg, LocalDate atual) {
        while (true) {
            System.out.print(msg);
            String s = scanner.nextLine().trim();
            if (s.isEmpty()) return atual;
            try { return LocalDate.parse(s); }
            catch (DateTimeParseException e) { System.out.println("Data inválida. Use AAAA-MM-DD."); }
        }
    }
}
