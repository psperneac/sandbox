import org.junit.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Calculates product markups for packaging. Uses BigDecimals for prices.
 */
public class Markup {
	public static void main(String[] args) {
        // initializes a context with the agents pipelines
        Context ctx = init();

        // pushes some requests through the pipeline
        Markup.push(ctx, "1299.99", 3, Type.FOOD);
        Markup.push(ctx, "5432.00", 1, Type.PHARMA);
        Markup.push(ctx, "12456.95", 4, Type.OTHER);
	}

    /**
     * creates a Product object, puts it in the context and calls execute on the agent
     */
    private static void push(Context ctx, String price, int people, Type type) {
        Product p = new Product();
        p.price(price).people(people).type(type);

        // ctx = ctx.createSubContext();  // to create a per-request context, while still being able to look-up the agent pipeline
        ctx.put(Product.class, p);

        Agent agent = (Agent) ctx.get(Agent.class);
        agent.execute(ctx);

    }

    /**
     * Builds a context and the agent pipeline. This sort of approach is very good when extending the agent
     * pipeline without affecting current processing. You just make another agent; put it in the pipeline at the point.
     * In the new agent you look up parameters in the context, do processing, provide results in the context
     *
     * Ideas for other agents:
     * - A thread pool agent that would execute the pipeline on multiple threads. This works very well in this case
     *   as no state is being saved in the agents. For this you would need a hierarchical context where you can
     *   make a sub-context that inherits from the parent context but can also hold per-request values
     * - You can programatically insert Agents in-between all other agents in the pipeline that time the
     *   execution.
     * - You can insert Logging agents at any point the pipeline to log the contents of the Context for debugging.
     *
     * @return
     */
    private static Context init() {
        Context ctx = new Context();

        Agent output = new FormatCurrencyAgent(new PrintAgent());

        MultiTypeAgent agent = new MultiTypeAgent();
        agent.add(Type.ELECTRONICS, new ByTypeMarkupAgent(Type.ELECTRONICS, output));
        agent.add(Type.FOOD, new ByTypeMarkupAgent(Type.FOOD, output));
        agent.add(Type.PHARMA, new ByTypeMarkupAgent(Type.PHARMA, output));
        agent.add(Type.OTHER, new ByTypeMarkupAgent(Type.OTHER, output));

        ctx.put(Agent.class, new FlatMarkupAgent(new PeopleMarkupAgent(agent)));
        return ctx;
    }

    @Test
    public void testProduct() {
        Product prod=new Product();
        assertEquals("0", prod.initialPrice().toString());
        assertEquals("0", prod.markedUpPrice().toString());
        assertEquals(Type.OTHER, prod.type());
        assertEquals(0, prod.people());

        prod.initialPrice("1299.99").people(3).type(Type.FOOD).markedUpPrice("1508.76");
        assertEquals("1299.99", prod.initialPrice().toString());
        assertEquals(3, prod.people());
        assertEquals(Type.FOOD, prod.type());
        assertEquals("1508.76", prod.markedUpPrice().toString());

        Context ctx = init();
        Agent agent = (Agent) ctx.get(Agent.class);

        /** sctx= // to capture request context */
        Markup.push(ctx, "1299.99" ,3,Type.FOOD);
        Product p= (Product) ctx.get(Product.class);
        assertEquals("1591.58", p.markedUpPrice().toString());

        Markup.push(ctx, "5432.00" ,1,Type.PHARMA);
        p= (Product) ctx.get(Product.class);
        assertEquals("6199.81", p.markedUpPrice().toString());

        Markup.push(ctx, "12456.95" ,4,Type.OTHER);
        p= (Product) ctx.get(Product.class);
        assertEquals("13707.63", p.markedUpPrice().toString());
    }
}

/** Just a place to put stuff that is accesible to agents */
class Context extends HashMap {

}

/** An execution unit that has access to its parameters through the context */
interface Agent {
	public void execute(Context ctx);
}

/** Base class for agents to inherit from. A proxy agent does some processing
 * on the objects in the context then calls its delegate
 */
class ProxyAgent implements Agent {

    protected Agent delegate;

    public ProxyAgent(Agent delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Context ctx) {
        getDelegate().execute(ctx);
    }

    public Agent getDelegate() { return delegate;}
}

/** Final agent in the pipeline, will print the Product on system.out. In a production system
 * this agent would save the Product/quote to the DB
 */
class PrintAgent implements Agent {

    @Override
    public void execute(Context ctx) {
        Product prod = (Product) ctx.get(Product.class);
        System.out.println(prod);
    }
}

/** Agent that applies the flat markup directly on the initialPrice. Will also set up
 * the markedUpPrice to the value of initialPrice
 */
class FlatMarkupAgent extends ProxyAgent{

    public FlatMarkupAgent(Agent delegate) {
        super(delegate);
    }

    @Override
    public void execute(Context ctx) {
        Product product = (Product) ctx.get(Product.class);

        product.initialPrice(product.initialPrice().add(product.initialPrice().multiply(Config.FLAT_MARKUP)));
        product.markedUpPrice(product.initialPrice());

        super.execute(ctx);
    }
}

/**
 * Applies the markup for people working on the job
 */
class PeopleMarkupAgent extends ProxyAgent {

    public PeopleMarkupAgent(Agent delegate) {
        super(delegate);
    }

    @Override
    public void execute(Context ctx) {

        Product product = (Product) ctx.get(Product.class);
        Config cfg = (Config) ctx.get(Config.class);

        product.markedUpPrice(product.markedUpPrice().add(
                product.initialPrice().multiply(Config.PEOPLE_MARKUP).multiply(new BigDecimal(product.people()))));

        super.execute(ctx);
    }
}

/**
 * Forwards the request to the correct branch by type of request.
 */
class MultiTypeAgent implements Agent {

    protected Map<Type, Agent> agents=new HashMap<Type, Agent>();

    public MultiTypeAgent add(Type type, Agent agent) {
        agents.put(type, agent);
        return this;
    }

    @Override
    public void execute(Context ctx) {
        Product prod = (Product) ctx.get(Product.class);
        Agent delegate = agents.get(prod.type());
        if(delegate!=null) {
            delegate.execute(ctx);
        }
    }
}

/** Uses a BigDecimal to remove the decimals beyond 2nd place. Uses the correct rounding */
class FormatCurrencyAgent extends ProxyAgent {
    public FormatCurrencyAgent(Agent delegate) {
        super(delegate);
    }

    @Override
    public void execute(Context ctx) {
        Product prod = (Product) ctx.get(Product.class);
        BigDecimal bd=prod.markedUpPrice().setScale(2, RoundingMode.HALF_EVEN);
        prod.markedUpPrice(bd);

        super.execute(ctx);
    }
}

/**
 * An interesting agent that uses a passed in function to apply a markup that depends on the
 * type of product. The function codes applying the percentage to the markedUpPrice. While I could have had 4
 * agents each applying the correct markup for a type of product, the difference between them was minimal
 * and I decided to abstract the computation in a Function
 */
class ByTypeMarkupAgent extends ProxyAgent {

    private final Function<BigDecimal> byTypeFunction;

    public ByTypeMarkupAgent(Type type, Agent delegate) {
        super(delegate);
        this.byTypeFunction = Config.getMarkupByType(type);
    }

    @Override
    public void execute(Context ctx) {

        Product prod = (Product) ctx.get(Product.class);
        prod.markedUpPrice(prod.markedUpPrice().add(byTypeFunction.f(ctx, prod.initialPrice())));

        getDelegate().execute(ctx);
    }
}

/**
 * Generic interface for a function with a single argument
 * @param <T>
 */
interface Function<T> {
    public T f(Context ctx, T value);
}

/** A markup function receives the percentage as a parameter to the constructor, then on processing
 * multiplies the percentage to the value passes in and returns the result.
 */
class MarkupFunction implements Function<BigDecimal> {
    private final BigDecimal markup;

    public MarkupFunction(BigDecimal markup) {
        this.markup = markup;
    }

    @Override
    public BigDecimal f(Context ctx, BigDecimal value) {
        return value.multiply(markup);
    }
};

/**
 * Generic config class. In a production system, you can build a Config class that loads all configuration
 * from the database and builds the markup function dynamically.
 */
class Config {
    public static final BigDecimal FLAT_MARKUP = new BigDecimal("0.05");
    public static final BigDecimal PEOPLE_MARKUP = new BigDecimal("0.012");
    public static final BigDecimal FOOD_MARKUP = new BigDecimal("0.13");
    public static final BigDecimal PHARMA_MARKUP = new BigDecimal("0.075");
    public static final BigDecimal ELECTRONICS_MARKUP = new BigDecimal("0.02");

    public static Function<BigDecimal> applyFoodMarkup = new MarkupFunction(FOOD_MARKUP);
    public static Function<BigDecimal> applyPharmaMarkup = new MarkupFunction(PHARMA_MARKUP);
    public static Function<BigDecimal> applyElectronicsMarkup = new MarkupFunction(ELECTRONICS_MARKUP);
    public static Function<BigDecimal> applyOtherMarkup = new MarkupFunction(new BigDecimal("0.0"));

    /** Helper function to be used during init to setup the agent pipeline */
    public static Function<BigDecimal> getMarkupByType(Type type) {
        switch(type) {
            case PHARMA:
                return applyPharmaMarkup;
            case FOOD:
                return applyFoodMarkup;
            case ELECTRONICS:
                return applyElectronicsMarkup;
            case OTHER:
            default:
                return applyOtherMarkup;
        }
    }
}

/** Types of jobs */
enum Type {
    PHARMA,
    ELECTRONICS,
    FOOD,
    OTHER
}

/** A Product to be marked up / a Job */
class Product {
    /** Initial introduced price. Only kept to be displayed at the end; we don't use it during processing */
    BigDecimal price=new BigDecimal(0);
    /** Initial price + flat markups, to be used for future markups */
	BigDecimal initialPrice=new BigDecimal(0);
    /** Marked up price at one moment */
	BigDecimal markedUpPrice=new BigDecimal(0);
    /** Type of the job */
	Type type=Type.OTHER;
    /** Number of people involved */
	int people=0;

	public Product() {

	}

    public Product price(String price) {
        this.price = new BigDecimal(price);
        this.initialPrice(price);
        return this;
    }

    public Product initialPrice(String price) {
        this.initialPrice=new BigDecimal(price);
        return this;
    }

    public Product initialPrice(BigDecimal price) {
        this.initialPrice=price;
        return this;
    }

    public Product markedUpPrice(String price) {
        this.markedUpPrice=new BigDecimal(price);
        return this;
    }

    public Product markedUpPrice(BigDecimal price) {
        this.markedUpPrice=price;
        return this;
    }

    public Product type(Type type) {
		this.type=type;
        return this;
	}

	public Product people(int people) {
		this.people=people;
        return this;
	}

    public BigDecimal price() { return price; }
    public BigDecimal initialPrice() { return initialPrice; }
	public BigDecimal markedUpPrice() { return markedUpPrice; }
	public Type type() { return type; }
	public int people() { return people; }

    public String toString() {
        StringBuilder b= new StringBuilder("Product{");
        b.append("Type: ");
        b.append(type());
        b.append(", Price: ");
        b.append(price());
        b.append(", People: ");
        b.append(people());
        b.append(", MarkedupPrice: ");
        b.append(markedUpPrice());
        b.append("}");
        return b.toString();
    }

}