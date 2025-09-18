package org.example;

// Import necessary libraries.
// 'com.fasterxml.jackson.databind.ObjectMapper' is a class from the Jackson library, used for converting JSON data to Java objects and vice-versa.
// 'com.google.ortools.Loader' is used to load the native C++ libraries required by OR-Tools.
// 'com.google.ortools.sat.*' imports all the classes from the CP-SAT solver, which we use to model and solve the problem.
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.ortools.Loader;
import com.google.ortools.sat.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// This is the main class that contains all the program logic.
public class Main {

    // --- Data Classes to Match JSON Structure ---
    // These static inner classes serve as templates that define the structure of the input JSON.
    // The ObjectMapper from the Jackson library requires these blueprints to correctly parse the JSON string into structured Java objects.
    // The variable names inside these classes must exactly match the keys in the JSON.

    /**
     * Represents the root of the JSON object, containing the configuration and the list of trains.
     */
    public static class InputData {
        public Config config;
        public List<Train> trains;
    }

    /**
     * Represents the 'config' object in the JSON, holding global parameters for the solver.
     */
    public static class Config {
        public int numCleaningSlots;   // Defines the maximum number of trains that can be assigned for cleaning.
        public int avgFleetDistance;   // The target average distance for the mileage balancing objective.
    }

    /**
     * Represents a single train object from the 'trains' array in the JSON.
     */
    public static class Train {
        public String id;
        public boolean requiresCleaning; // A boolean indicating if this train is a candidate for a cleaning assignment.
        public int distanceTravelled;    // The train's current mileage, used for the balancing calculation.
        public int brandingScore;        // A numerical score representing the branding priority.
        public int stablingScore;        // A numerical score representing the stabling priority.
    }

    /**
     * This is the main method, the entry point where the program execution begins.
     * @param args Command-line arguments (not used in this program).
     * @throws IOException if there is an error during JSON parsing.
     */
    public static void main(String[] args) throws IOException {
        // This statement is required to load the native C++ libraries that power the OR-Tools solver.
        // The Java code acts as a wrapper around this high-performance C++ engine.
        Loader.loadNativeLibraries();

        // --- Model Weights ---
        // These constants represent the weights for our different objectives. They allow us to define
        // the relative importance of each goal. A higher weight means the solver will prioritize
        // that objective more heavily.
        final int WEIGHT_BRANDING = 10;
        final int WEIGHT_STABLING = 5;
        final int WEIGHT_MILEAGE_PENALTY = 1;

        // --- JSON Input ---
        // This is a sample JSON string for testing purposes. In a real application,
        // this string would come from an external source, like an API request.
        String jsonInput = "{\n" +
                "  \"config\": {\n" +
                "    \"numCleaningSlots\": 2,\n" +
                "    \"avgFleetDistance\": 6500\n" +
                "  },\n" +
                "  \"trains\": [\n" +
                "    {\"id\": \"T01\", \"requiresCleaning\": true, \"distanceTravelled\": 6800, \"brandingScore\": 90, \"stablingScore\": 80},\n" +
                "    {\"id\": \"T03\", \"requiresCleaning\": true, \"distanceTravelled\": 6400, \"brandingScore\": 50, \"stablingScore\": 60},\n" +
                "    {\"id\": \"T04\", \"requiresCleaning\": false, \"distanceTravelled\": 6550, \"brandingScore\": 70, \"stablingScore\": 75},\n" +
                "    {\"id\": \"T05\", \"requiresCleaning\": true, \"distanceTravelled\": 7500, \"brandingScore\": 98, \"stablingScore\": 92},\n" +
                "    {\"id\": \"T06\", \"requiresCleaning\": true, \"distanceTravelled\": 5500, \"brandingScore\": 30, \"stablingScore\": 40}\n" +
                "  ]\n" +
                "}";

        // Create an instance of the Jackson ObjectMapper to handle the JSON deserialization.
        ObjectMapper mapper = new ObjectMapper();
        // The readValue method parses the 'jsonInput' string and maps its contents
        // into a new 'InputData' object, following the structure of our data classes.
        InputData data = mapper.readValue(jsonInput, Main.InputData.class);

        // --- Model Setup ---
        // Create an instance of CpModel. This object will act as a container
        // for all the variables, constraints, and objectives that define our problem.
        CpModel model = new CpModel();

        // --- Decision Variables ---
        // These variables represent the choices that the solver can make.
        // We use a Map to associate each train's ID with its corresponding decision variable.
        Map<String, IntVar> isAssigned = new HashMap<>();
        for (Train t : data.trains) {
            // A decision is only needed for trains that are candidates for cleaning.
            if (t.requiresCleaning) {
                // We create a boolean variable ('BoolVar') for each candidate train.
                // This variable can only take the value 0 (false) or 1 (true).
                // The solver's task is to find the optimal 0/1 value for each of these variables.
                isAssigned.put(t.id, model.newBoolVar("isAssigned_" + t.id));
            }
        }

        // --- Constraints ---
        // Constraints are rules that the final solution must obey.
        List<IntVar> assignedVars = new ArrayList<>(isAssigned.values());
        // This constraint states that the sum of all 'isAssigned' variables (the total number of
        // trains chosen for cleaning) must be less than or equal to the number of available slots.
        // This is a hard limit that the solver cannot violate.
        model.addLessOrEqual(LinearExpr.sum(assignedVars.toArray(new IntVar[0])), data.config.numCleaningSlots);

        // --- Objective Function ---
        // This section defines the objective function for the optimization problem.
        // The goal is to build a single mathematical expression that represents the "quality" of a solution.
        // The solver will attempt to find a solution that MAXIMIZES the value of this expression.
        LinearExprBuilder objective = LinearExpr.newBuilder();
        for (Train t : data.trains) {
            // For each train, calculate the penalty associated with its mileage deviation.
            long deviation = Math.abs(t.distanceTravelled - data.config.avgFleetDistance);
            long penalty = deviation * WEIGHT_MILEAGE_PENALTY;

            // Handle the trains that are candidates for a cleaning assignment.
            if (t.requiresCleaning) {
                IntVar assignmentVar = isAssigned.get(t.id);
                // Calculate the reward for assigning this specific train, based on its scores and weights.
                int reward = (t.brandingScore * WEIGHT_BRANDING) + (t.stablingScore * WEIGHT_STABLING);

                // This is a standard technique to model conditional rewards and penalties.
                // The goal is to maximize: (reward * assignmentVar) - (penalty * (1 - assignmentVar)).
                // This expression can be algebraically simplified to: (reward + penalty) * assignmentVar - penalty.
                // This simplified form is more efficient and uses basic API methods.
                objective.addTerm(assignmentVar, reward + penalty);
                objective.add(-penalty);

            } else {
                // If a train does not require cleaning, it is part of the service fleet by default.
                // Therefore, its mileage penalty is unconditionally subtracted from the total score.
                objective.add(-penalty);

            }
        }
        // Instruct the model that the goal is to maximize the value of the objective function we just built.
        model.maximize(objective.build());

        // --- Solve the Model ---
        // Create an instance of the solver.
        CpSolver solver = new CpSolver();
        // Pass the fully defined model to the solver and initiate the search for a solution.
        CpSolverStatus status = solver.solve(model);

        // --- Interpret and Display the Results ---
        // Check if the solver successfully found a feasible or optimal solution.
        if (status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE) {
            System.out.println("Success: Solver found an optimal solution!");
            System.out.println("Maximized Objective Value: " + solver.objectiveValue() + "\n");
            System.out.println("--- Optimal Assignment Plan ---");

            // Create empty lists to categorize the trains based on the solver's decisions.
            List<Train> assignedForCleaning = new ArrayList<>();
            List<Train> goingToService = new ArrayList<>();

            // Iterate through all trains to read the results from the solver.
            for (Train t : data.trains) {
                // To determine if a train was assigned, we check if its decision variable exists
                // and if the solver set its value to 1.
                if (isAssigned.containsKey(t.id) && solver.value(isAssigned.get(t.id)) == 1) {
                    assignedForCleaning.add(t);
                } else {
                    goingToService.add(t);
                }
            }

            // This final section is for displaying the results to the console.
            // In a production environment, this part would be replaced with logic to serialize
            // the 'assignedForCleaning' and 'goingToService' lists back into a JSON format
            // to be returned by an API.
            System.out.println("--> Assigned for Cleaning (" + assignedForCleaning.size() + "/" + data.config.numCleaningSlots + " slots used):");
            if (assignedForCleaning.isEmpty()){
                System.out.println("  None.");
            } else {
                for(Train t : assignedForCleaning) {
                    System.out.printf("  - Train %s (Branding: %d, Stabling: %d)\n", t.id, t.brandingScore, t.stablingScore);
                }
            }

            System.out.println("\n--> Proceeding to Service (Unassigned Fleet):");
            if (goingToService.isEmpty()){
                System.out.println("  None.");
            } else {
                for(Train t : goingToService) {
                    long dev = Math.abs(t.distanceTravelled - data.config.avgFleetDistance);
                    System.out.printf("  - Train %s (Mileage Deviation: %d km)\n", t.id, dev);
                }
            }
        } else {
            // This block executes if the solver could not find a valid solution.
            System.out.println("Error: No solution could be found. Status: " + status);
        }
    }
}