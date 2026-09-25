import dao.ArquivoSequencial;
import model.Carro;
import service.Importador;
import service.OrdenacaoExterna;
import service.GerenciadorIndices;

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
        System.out.println("9 - Índices / TP2 — Árvore B+");
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
        String nome = lerStringObrigatoria("Modelo/nome do carro: ");
        List<String> caracteristicas = lerLista("Características separadas por | (pode deixar vazio): ");
        int ano = lerAno("Ano: ");
        LocalDate data = lerData("Data de registro (AAAA-MM-DD): ");

        Carro p = new Carro(0, "", nome, data, caracteristicas, ano);
        int id = indices.executarAlteracaoSequencial(() -> arquivo.create(p));
        System.out.println("Registro criado com ID " + id + " e código " + p.getCodigo());
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

        System.out.println("Atual: " + atual);
        System.out.println("Pressione ENTER para manter o valor atual.");

        String nome = lerOpcional("Modelo/nome [" + atual.getNome() + "]: ", atual.getNome());
        String caracteristicasTexto = lerOpcional("Características separadas por | [" + String.join("|", atual.getCaracteristicas()) + "]: ",
                String.join("|", atual.getCaracteristicas()));
        List<String> caracteristicas = parseLista(caracteristicasTexto);
        int ano = lerAnoOpcional("Ano [" + atual.getAno() + "]: ", atual.getAno());
        LocalDate data = lerDataOpcional("Data [" + atual.getDataRegistro() + "]: ", atual.getDataRegistro());

        Carro novo = new Carro(id, atual.getCodigo(), nome, data, caracteristicas, ano);
        boolean ok = indices.executarAlteracaoSequencial(() -> arquivo.update(novo));
        System.out.println(ok ? "Registro atualizado." : "Registro não encontrado.");
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
            System.out.println("========== ÍNDICES / TP2 — B+ ==========");
            System.out.println("1 - Criar/reconstruir B+ a partir de dados.db");
            System.out.println("2 - Buscar ID pela B+ (leitura direta)");
            System.out.println("3 - Informações da B+");
            System.out.println("4 - Validar B+ e posições dos dados");
            System.out.println("5 - Comparar leitura sequencial e B+");
            System.out.println("0 - Voltar");
            opcao = lerInt("Escolha: ", -1);
            switch (opcao) {
                case 1:
                    int ordem = lerInt("Ordem m (máximo de filhos; 3 a 4096): ", -1);
                    long inicio = System.nanoTime();
                    indices.reconstruir(ordem);
                    System.out.println("Reconstrução concluída em "
                            + (System.nanoTime() - inicio) / 1_000_000 + " ms.");
                    System.out.println(indices.informacoes());
                    break;
                case 2:
                    Carro carro = indices.buscar(lerInt("ID: ", -1));
                    System.out.println(carro == null ? "Registro não encontrado." : carro);
                    break;
                case 3: System.out.println(indices.informacoes()); break;
                case 4: System.out.println(indices.validar()); break;
                case 5:
                    int id = lerInt("ID para comparar: ", -1);
                    int repeticoes = lerInt("Repetições (1 a 1000): ", -1);
                    System.out.println(indices.compararBuscas(new int[]{id}, repeticoes));
                    break;
                case 0: break;
                default: System.out.println("Opção inválida.");
            }
        } while (opcao != 0);
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
