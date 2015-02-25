/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2015
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.parser;

import java.util.ArrayList;
import java.util.HashMap;

import com.ibm.bi.dml.parser.LanguageException.LanguageErrorCodes;


public class ParameterizedBuiltinFunctionExpression extends DataIdentifier 
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2015\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	
	private ParameterizedBuiltinFunctionOp _opcode;
	private HashMap<String,Expression> _varParams;
	
	public static ParameterizedBuiltinFunctionExpression getParamBuiltinFunctionExpression(String functionName, ArrayList<ParameterExpression> paramExprsPassed,
			String fileName, int blp, int bcp, int elp, int ecp){
	
		if (functionName == null || paramExprsPassed == null)
			return null;
		
		// check if the function name is built-in function
		//	 (assign built-in function op if function is built-in)
		Expression.ParameterizedBuiltinFunctionOp pbifop = null;	
		if (functionName.equals("cumulativeProbability"))
			pbifop = Expression.ParameterizedBuiltinFunctionOp.CDF;
		//'groupedAggregate' for backwards compatibility
		else if (functionName.equals("aggregate") || functionName.equals("groupedAggregate"))
			pbifop = Expression.ParameterizedBuiltinFunctionOp.GROUPEDAGG;
		else if (functionName.equals("removeEmpty"))
			pbifop = Expression.ParameterizedBuiltinFunctionOp.RMEMPTY;
		else if (functionName.equals("replace"))
			pbifop = Expression.ParameterizedBuiltinFunctionOp.REPLACE;
		else if (functionName.equals("order"))
			pbifop = Expression.ParameterizedBuiltinFunctionOp.ORDER;
		else
			return null;
		
		HashMap<String,Expression> varParams = new HashMap<String,Expression>();
		for (ParameterExpression pexpr : paramExprsPassed)
			varParams.put(pexpr.getName(), pexpr.getExpr());
		
		ParameterizedBuiltinFunctionExpression retVal = new ParameterizedBuiltinFunctionExpression(pbifop,varParams,
				fileName, blp, bcp, elp, ecp);
		return retVal;
	} // end method getBuiltinFunctionExpression
	
			
	public ParameterizedBuiltinFunctionExpression(ParameterizedBuiltinFunctionOp op, HashMap<String,Expression> varParams,
			String filename, int blp, int bcp, int elp, int ecp) {
		_kind = Kind.ParameterizedBuiltinFunctionOp;
		_opcode = op;
		_varParams = varParams;
		this.setAllPositions(filename, blp, bcp, elp, ecp);
	}
   
	public ParameterizedBuiltinFunctionExpression(String filename, int blp, int bcp, int elp, int ecp) {
		_kind = Kind.ParameterizedBuiltinFunctionOp;
		_opcode = ParameterizedBuiltinFunctionOp.INVALID;
		_varParams = new HashMap<String,Expression>();
		this.setAllPositions(filename, blp, bcp, elp, ecp);
	}
    
	public Expression rewriteExpression(String prefix) throws LanguageException {
		
		HashMap<String,Expression> newVarParams = new HashMap<String,Expression>();
		for (String key : _varParams.keySet()){
			Expression newExpr = _varParams.get(key).rewriteExpression(prefix);
			newVarParams.put(key, newExpr);
		}	
		ParameterizedBuiltinFunctionExpression retVal = new ParameterizedBuiltinFunctionExpression(_opcode, newVarParams,
				this.getFilename(), this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
	
		return retVal;
	}

	public void setOpcode(ParameterizedBuiltinFunctionOp op) {
		_opcode = op;
	}
	
	public ParameterizedBuiltinFunctionOp getOpCode() {
		return _opcode;
	}
	
	public HashMap<String,Expression> getVarParams() {
		return _varParams;
	}
	
	public Expression getVarParam(String name) {
		return _varParams.get(name);
	}

	public void addVarParam(String name, Expression value){
		_varParams.put(name, value);
	}
	
	public void removeVarParam(String name) {
		_varParams.remove(name);
	}
	
	/**
	 * Validate parse tree : Process BuiltinFunction Expression in an assignment
	 * statement
	 * 
	 * @throws LanguageException
	 */
	@Override
	public void validateExpression(HashMap<String, DataIdentifier> ids, HashMap<String, ConstIdentifier> constVars, boolean conditional)
			throws LanguageException 
	{		
		// validate all input parameters
		for ( String s : getVarParams().keySet() ) {
			Expression paramExpr = getVarParam(s);
			
			if (paramExpr instanceof FunctionCallIdentifier){
				raiseValidateError("UDF function call not supported as parameter to built-in function call", false);
			}
			
			paramExpr.validateExpression(ids, constVars, conditional);
		}
		
		String outputName = getTempName();
		DataIdentifier output = new DataIdentifier(outputName);
		//output.setProperties(this.getFirstExpr().getOutput());
		this.setOutput(output);

		// IMPORTANT: for each operation, one must handle unnamed parameters
		
		switch (this.getOpCode()) {
		
		case GROUPEDAGG:
			int colwise = -1;
			if (getVarParam(Statement.GAGG_TARGET)  == null || getVarParam(Statement.GAGG_GROUPS) == null){
				raiseValidateError("Must define both target and groups and both must have same dimensions", conditional);
			}
			if (getVarParam(Statement.GAGG_TARGET) instanceof DataIdentifier && getVarParam(Statement.GAGG_GROUPS) instanceof DataIdentifier && (getVarParam(Statement.GAGG_WEIGHTS) == null || getVarParam(Statement.GAGG_WEIGHTS) instanceof DataIdentifier))
			{
				
				DataIdentifier targetid = (DataIdentifier)getVarParam(Statement.GAGG_TARGET);
				DataIdentifier groupsid = (DataIdentifier)getVarParam(Statement.GAGG_GROUPS);
				DataIdentifier weightsid = (DataIdentifier)getVarParam(Statement.GAGG_WEIGHTS);
			
				if ( targetid.dimsKnown() ) {
					colwise = targetid.getDim1() > targetid.getDim2() ? 1 : 0;
				}
				else if ( groupsid.dimsKnown() ) {
					colwise = groupsid.getDim1() > groupsid.getDim2() ? 1 : 0;
				}
				else if ( weightsid != null && weightsid.dimsKnown() ) {
					colwise = weightsid.getDim1() > weightsid.getDim2() ? 1 : 0;
				}
				
				//precompute number of rows and columns because target can be row or column vector
				long rowsTarget = Math.max(targetid.getDim1(),targetid.getDim2());
				long colsTarget = Math.min(targetid.getDim1(),targetid.getDim2());
				
				if( targetid.dimsKnown() && groupsid.dimsKnown() &&
					(rowsTarget != groupsid.getDim1() || colsTarget != groupsid.getDim2()) )
				{					
					raiseValidateError("target and groups must have same dimensions -- " 
							+ " targetid dims: " + targetid.getDim1() +" rows, " + targetid.getDim2() + " cols -- groupsid dims: " + groupsid.getDim1() + " rows, " + groupsid.getDim2() + " cols ", conditional);
				}
				
				if( weightsid != null && (targetid.dimsKnown() && weightsid.dimsKnown()) &&
					(rowsTarget != weightsid.getDim1() || colsTarget != weightsid.getDim2() ))
				{		
					raiseValidateError("target and weights must have same dimensions -- "
							+ " targetid dims: " + targetid.getDim1() +" rows, " + targetid.getDim2() + " cols -- weightsid dims: " + weightsid.getDim1() + " rows, " + weightsid.getDim2() + " cols ", conditional);
				}
			}
			
			
			if (getVarParam(Statement.GAGG_FN) == null){
				raiseValidateError("must define function name (fname=<function name>) for groupedAggregate()", conditional);
			}
			
			Expression functParam = getVarParam(Statement.GAGG_FN);
			
			if (functParam instanceof Identifier){
			
				// standardize to lowercase and dequote fname
				String fnameStr = getVarParam(Statement.GAGG_FN).toString();
				
				
				// check that IF fname="centralmoment" THEN order=m is defined, where m=2,3,4 
				// check ELSE IF fname is allowed
				if(fnameStr.equals(Statement.GAGG_FN_CM)){
					String orderStr = getVarParam(Statement.GAGG_FN_CM_ORDER) == null ? null : getVarParam(Statement.GAGG_FN_CM_ORDER).toString();
					if (orderStr == null || !(orderStr.equals("2") || orderStr.equals("3") || orderStr.equals("4"))){
						raiseValidateError("for centralmoment, must define order.  Order must be equal to 2,3, or 4", conditional);
					}
				}
				else if (fnameStr.equals(Statement.GAGG_FN_COUNT) 
						|| fnameStr.equals(Statement.GAGG_FN_SUM) 
						|| fnameStr.equals(Statement.GAGG_FN_MEAN)
						|| fnameStr.equals(Statement.GAGG_FN_VARIANCE)){}
				else { 
					raiseValidateError("fname is " + fnameStr + " but must be either centeralmoment, count, sum, mean, variance", conditional);
				}
			}
			
			long outputDim1 = -1, outputDim2 = -1;
			Identifier numGroups = (Identifier) getVarParam(Statement.GAGG_NUM_GROUPS);
			if ( numGroups != null && numGroups instanceof ConstIdentifier) {
				long ngroups = ((ConstIdentifier)numGroups).getLongValue();
				if ( colwise == 1 ) {
					outputDim1 = ngroups;
					outputDim2 = 1;
				}
				else if ( colwise == 0 ) {
					outputDim1 = 1;
					outputDim2 = ngroups;
				}
			}
			
			// Output is a matrix with unknown dims
			output.setDataType(DataType.MATRIX);
			output.setValueType(ValueType.DOUBLE);
			output.setDimensions(outputDim1, outputDim2);

			break; 
			
		case CDF:
			/*
			 * Usage: p = cumulativeProbability(x, dist="chisq", df=20);
			 */
			
			// CDF expects one unnamed parameter
			// it must be renamed as "quantile" 
			// (i.e., we must compute P(X <= x) where x is called as "quantile" )
			
			// check if quantile is of type SCALAR
			if ( getVarParam("target").getOutput().getDataType() != DataType.SCALAR ) {
				raiseValidateError("Quantile to cumulativeProbability() must be a scalar value.", conditional, LanguageErrorCodes.INVALID_PARAMETERS);
			}
			
			// Output is a scalar
			output.setDataType(DataType.SCALAR);
			output.setValueType(ValueType.DOUBLE);
			output.setDimensions(0, 0);

			break;
			
		case RMEMPTY:
		{
			//check existence and correctness of arguments
			Expression target = getVarParam("target");
			if( target==null ) {
				raiseValidateError("Named parameter 'target' missing. Please specify the input matrix.", conditional, LanguageErrorCodes.INVALID_PARAMETERS);
			}
			else if( target.getOutput().getDataType() != DataType.MATRIX ){
				raiseValidateError("Input matrix 'target' is of type '"+target.getOutput().getDataType()+"'. Please specify the input matrix.", conditional, LanguageErrorCodes.INVALID_PARAMETERS);
			}
			
			Expression margin = getVarParam("margin");
			if( margin==null ){
				raiseValidateError("Named parameter 'margin' missing. Please specify 'rows' or 'cols'.", conditional, LanguageErrorCodes.INVALID_PARAMETERS);
			}
			else if( !(margin instanceof DataIdentifier) && !margin.toString().equals("rows") && !margin.toString().equals("cols") ){
				raiseValidateError("Named parameter 'margin' has an invalid value '"+margin.toString()+"'. Please specify 'rows' or 'cols'.", conditional, LanguageErrorCodes.INVALID_PARAMETERS);
			}
			
			// Output is a matrix with unknown dims
			output.setDataType(DataType.MATRIX);
			output.setValueType(ValueType.DOUBLE);
			output.setDimensions(-1, -1);
			
			break;
		}
		
		case REPLACE:
		{
			//check existence and correctness of arguments
			Expression target = getVarParam("target");
			if( target==null ) {				
				raiseValidateError("Named parameter 'target' missing. Please specify the input matrix.", conditional, LanguageErrorCodes.INVALID_PARAMETERS);
			}
			else if( target.getOutput().getDataType() != DataType.MATRIX ){
				raiseValidateError("Input matrix 'target' is of type '"+target.getOutput().getDataType()+"'. Please specify the input matrix.", conditional, LanguageErrorCodes.INVALID_PARAMETERS);
			}	
			
			Expression pattern = getVarParam("pattern");
			if( pattern==null ) {
				raiseValidateError("Named parameter 'pattern' missing. Please specify the replacement pattern.", conditional, LanguageErrorCodes.INVALID_PARAMETERS);
			}
			else if( pattern.getOutput().getDataType() != DataType.SCALAR ){				
				raiseValidateError("Replacement pattern 'pattern' is of type '"+pattern.getOutput().getDataType()+"'. Please, specify a scalar replacement pattern.", conditional, LanguageErrorCodes.INVALID_PARAMETERS);
			}	
			
			Expression replacement = getVarParam("replacement");
			if( replacement==null ) {
				raiseValidateError("Named parameter 'replacement' missing. Please specify the replacement value.", conditional, LanguageErrorCodes.INVALID_PARAMETERS);
			}
			else if( replacement.getOutput().getDataType() != DataType.SCALAR ){	
				raiseValidateError("Replacement value 'replacement' is of type '"+replacement.getOutput().getDataType()+"'. Please, specify a scalar replacement value.", conditional, LanguageErrorCodes.INVALID_PARAMETERS);
			}	
			
			// Output is a matrix with same dims as input
			output.setDataType(DataType.MATRIX);
			output.setValueType(ValueType.DOUBLE);
			output.setDimensions(target.getOutput().getDim1(), target.getOutput().getDim2());
			
			break;
		}
		
		case ORDER:
		{
			//check existence and correctness of arguments
			Expression target = getVarParam("target"); //[MANDATORY] TARGET
			if( target==null ) {				
				raiseValidateError("Named parameter 'target' missing. Please specify the input matrix.", conditional, LanguageErrorCodes.INVALID_PARAMETERS);
			}
			else if( target.getOutput().getDataType() != DataType.MATRIX ){
				raiseValidateError("Input matrix 'target' is of type '"+target.getOutput().getDataType()+"'. Please specify the input matrix.", conditional, LanguageErrorCodes.INVALID_PARAMETERS);
			}	
			
			Expression orderby = getVarParam("by"); //[OPTIONAL] BY
			if( orderby == null ) { //default first column, good fit for vectors
				orderby = new IntIdentifier(1, "1", -1, -1, -1, -1);
				addVarParam("by", orderby);
			}
			else if( orderby !=null && orderby.getOutput().getDataType() != DataType.SCALAR ){				
				raiseValidateError("Orderby column 'by' is of type '"+orderby.getOutput().getDataType()+"'. Please, specify a scalar order by column index.", conditional, LanguageErrorCodes.INVALID_PARAMETERS);
			}	
			
			Expression decreasing = getVarParam("decreasing"); //[OPTIONAL] DECREASING
			if( decreasing == null ) { //default: ascending
				addVarParam("decreasing", new BooleanIdentifier(false, "false", -1, -1, -1, -1));
			}
			else if( decreasing!=null && decreasing.getOutput().getDataType() != DataType.SCALAR ){				
				raiseValidateError("Ordering 'decreasing' is of type '"+decreasing.getOutput().getDataType()+"', '"+decreasing.getOutput().getValueType()+"'. Please, specify 'decreasing' as a scalar boolean.", conditional, LanguageErrorCodes.INVALID_PARAMETERS);
			}
			
			Expression indexreturn = getVarParam("index.return"); //[OPTIONAL] DECREASING
			if( indexreturn == null ) { //default: sorted data
				indexreturn = new BooleanIdentifier(false, "false", -1, -1, -1, -1);
				addVarParam("index.return", indexreturn);
			}
			else if( indexreturn!=null && indexreturn.getOutput().getDataType() != DataType.SCALAR ){				
				raiseValidateError("Return type 'index.return' is of type '"+indexreturn.getOutput().getDataType()+"', '"+indexreturn.getOutput().getValueType()+"'. Please, specify 'indexreturn' as a scalar boolean.", conditional, LanguageErrorCodes.INVALID_PARAMETERS);
			}
			long dim2 = ( indexreturn instanceof BooleanIdentifier ) ? 
					((BooleanIdentifier)indexreturn).getValue() ? 1: target.getOutput().getDim2() : -1; 
			
			// Output is a matrix with same dims as input
			output.setDataType(DataType.MATRIX);
			output.setValueType(ValueType.DOUBLE);
			output.setDimensions(target.getOutput().getDim1(), dim2 );
			
			break;
		}

			
		default: //always unconditional (because unsupported operation)
			raiseValidateError("Unsupported parameterized function "+ this.getOpCode(), false, LanguageErrorCodes.INVALID_PARAMETERS);
		}
		return;
	}

	public String toString() {
		StringBuilder sb = new StringBuilder(_opcode.toString() + "(");

		 for (String key : _varParams.keySet()){
			 sb.append("," + key + "=" + _varParams.get(key));
		 }
		sb.append(" )");
		return sb.toString();
	}

	@Override
	public VariableSet variablesRead() {
		VariableSet result = new VariableSet();
		for (String s : _varParams.keySet()) {
			result.addVariables ( _varParams.get(s).variablesRead() );
		}
		return result;
	}

	@Override
	public VariableSet variablesUpdated() {
		VariableSet result = new VariableSet();
		for (String s : _varParams.keySet()) {
			result.addVariables ( _varParams.get(s).variablesUpdated() );
		}
		result.addVariable(((DataIdentifier)this.getOutput()).getName(), (DataIdentifier)this.getOutput());
		return result;
	}

	@Override
	public boolean multipleReturns() {
		return false;
	}
}