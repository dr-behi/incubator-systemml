/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2015
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.hops;

import com.ibm.bi.dml.hops.rewrite.HopRewriteUtils;
import com.ibm.bi.dml.lops.Aggregate;
import com.ibm.bi.dml.lops.CentralMoment;
import com.ibm.bi.dml.lops.CoVariance;
import com.ibm.bi.dml.lops.CombineBinary;
import com.ibm.bi.dml.lops.CombineTernary;
import com.ibm.bi.dml.lops.Group;
import com.ibm.bi.dml.lops.Lop;
import com.ibm.bi.dml.lops.LopsException;
import com.ibm.bi.dml.lops.PickByCount;
import com.ibm.bi.dml.lops.SortKeys;
import com.ibm.bi.dml.lops.Ternary;
import com.ibm.bi.dml.lops.UnaryCP;
import com.ibm.bi.dml.lops.CombineBinary.OperationTypes;
import com.ibm.bi.dml.lops.LopProperties.ExecType;
import com.ibm.bi.dml.lops.PartialAggregate.CorrectionLocationType;
import com.ibm.bi.dml.parser.Statement;
import com.ibm.bi.dml.parser.Expression.DataType;
import com.ibm.bi.dml.parser.Expression.ValueType;
import com.ibm.bi.dml.runtime.matrix.MatrixCharacteristics;
import com.ibm.bi.dml.sql.sqllops.ISQLSelect;
import com.ibm.bi.dml.sql.sqllops.SQLCondition;
import com.ibm.bi.dml.sql.sqllops.SQLJoin;
import com.ibm.bi.dml.sql.sqllops.SQLLopProperties;
import com.ibm.bi.dml.sql.sqllops.SQLLops;
import com.ibm.bi.dml.sql.sqllops.SQLSelectStatement;
import com.ibm.bi.dml.sql.sqllops.SQLTableReference;
import com.ibm.bi.dml.sql.sqllops.SQLUnion;
import com.ibm.bi.dml.sql.sqllops.SQLCondition.BOOLOP;
import com.ibm.bi.dml.sql.sqllops.SQLLopProperties.AGGREGATIONTYPE;
import com.ibm.bi.dml.sql.sqllops.SQLLopProperties.JOINTYPE;
import com.ibm.bi.dml.sql.sqllops.SQLLops.GENERATES;
import com.ibm.bi.dml.sql.sqllops.SQLUnion.UNIONTYPE;

/* Primary use cases for now, are
 * 		quantile (<n-1-matrix>, <n-1-matrix>, <literal>):      quantile (A, w, 0.5)
 * 		quantile (<n-1-matrix>, <n-1-matrix>, <scalar>):       quantile (A, w, s)
 * 		interquantile (<n-1-matrix>, <n-1-matrix>, <scalar>):  interquantile (A, w, s)
 * 
 * Keep in mind, that we also have binaries for it w/o weights.
 * 	quantile (A, 0.5)
 * 	quantile (A, s)
 * 	interquantile (A, s)
 * 
 * Note: this hop should be called AggTernaryOp in consistency with AggUnaryOp and AggBinaryOp;
 * however, since there does not exist a real TernaryOp yet - we can leave it as is for now. 
 */

public class TernaryOp extends Hop 
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2015\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	
	public static boolean ALLOW_CTABLE_SEQUENCE_REWRITES = true;
	
	private OpOp3 _op = null;
	
	//ctable specific flags 
	// flag to indicate the existence of additional inputs representing output dimensions
	private boolean _dimInputsPresent = false;
	private boolean _disjointInputs = false;
	
	
	private TernaryOp() {
		//default constructor for clone
	}
	
	public TernaryOp(String l, DataType dt, ValueType vt, Hop.OpOp3 o,
			Hop inp1, Hop inp2, Hop inp3) {
		super(Hop.Kind.TernaryOp, l, dt, vt);
		_op = o;
		getInput().add(0, inp1);
		getInput().add(1, inp2);
		getInput().add(2, inp3);
		inp1.getParent().add(this);
		inp2.getParent().add(this);
		inp3.getParent().add(this);
	}
	
	// Constructor the case where TertiaryOp (table, in particular) has
	// output dimensions
	public TernaryOp(String l, DataType dt, ValueType vt, Hop.OpOp3 o,
			Hop inp1, Hop inp2, Hop inp3, Hop inp4, Hop inp5) {
		super(Hop.Kind.TernaryOp, l, dt, vt);
		_op = o;
		getInput().add(0, inp1);
		getInput().add(1, inp2);
		getInput().add(2, inp3);
		getInput().add(3, inp4);
		getInput().add(4, inp5);
		inp1.getParent().add(this);
		inp2.getParent().add(this);
		inp3.getParent().add(this);
		inp4.getParent().add(this);
		inp5.getParent().add(this);
		_dimInputsPresent = true;
	}
	
	public OpOp3 getOp(){
		return _op;
	}
	
	public void setDisjointInputs(boolean flag){
		_disjointInputs = flag;
	}
	
	@Override
	public Lop constructLops() 
		throws HopsException, LopsException 
	{	
		if (getLops() == null) 
		{
			try 
			{
				switch( _op ) {
					case CENTRALMOMENT:
						constructLopsCentralMoment();
						break;
						
					case COVARIANCE:
						constructLopsCovariance();
						break;
						
					case QUANTILE:
					case INTERQUANTILE:
						constructLopsQuantile();
						break;
						
					case CTABLE:
						constructLopsCtable();
						break;
						
					default:
						throw new HopsException(this.printErrorLocation() + "Unknown TernaryOp (" + _op + ") while constructing Lops \n");

				}
			} 
			catch(LopsException e) {
				throw new HopsException(this.printErrorLocation() + "error constructing Lops for TernaryOp Hop " , e);
			}
		}
	
		return getLops();
	}

	/**
	 * Method to construct LOPs when op = CENTRAILMOMENT.
	 * 
	 * @throws HopsException
	 * @throws LopsException
	 */
	private void constructLopsCentralMoment() throws HopsException, LopsException {
		
		if ( _op != OpOp3.CENTRALMOMENT )
			throw new HopsException("Unexpected operation: " + _op + ", expecting " + OpOp3.CENTRALMOMENT );
		
		ExecType et = optFindExecType();
		if ( et == ExecType.SPARK )  {
			// throw new HopsException("constructLopsCentralMoment for TertiaryOp not implemented for Spark");
			et = ExecType.CP;
		}
		if ( et == ExecType.MR ) {
			CombineBinary combine = CombineBinary.constructCombineLop(
					OperationTypes.PreCentralMoment, 
					getInput().get(0).constructLops(), 
					getInput().get(1).constructLops(), 
					DataType.MATRIX, getValueType());
			combine.getOutputParameters().setDimensions(
					getInput().get(0).getDim1(),
					getInput().get(0).getDim2(),
					getInput().get(0).getRowsInBlock(),
					getInput().get(0).getColsInBlock(), 
					getInput().get(0).getNnz());
			
			CentralMoment cm = new CentralMoment(combine, getInput()
					.get(2).constructLops(), DataType.MATRIX,
					getValueType(), et);
			cm.getOutputParameters().setDimensions(1, 1, 0, 0, -1);
			
			cm.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
			
			UnaryCP unary1 = new UnaryCP(cm, HopsOpOp1LopsUS
					.get(OpOp1.CAST_AS_SCALAR), getDataType(),
					getValueType());
			unary1.getOutputParameters().setDimensions(0, 0, 0, 0, -1);
			unary1.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
			setLops(unary1);
		}
		else {
			//System.out.println("CM Tertiary executing in CP...");
			CentralMoment cm = new CentralMoment(
					getInput().get(0).constructLops(),
					getInput().get(1).constructLops(),
					getInput().get(2).constructLops(),
					getDataType(), getValueType(), et);
			cm.getOutputParameters().setDimensions(0, 0, 0, 0, -1);
			cm.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
			setLops(cm);
		}
	}
	
	/**
	 * Method to construct LOPs when op = COVARIANCE.
	 * 
	 * @throws HopsException
	 * @throws LopsException
	 */
	private void constructLopsCovariance() throws HopsException, LopsException {
		
		if ( _op != OpOp3.COVARIANCE )
			throw new HopsException("Unexpected operation: " + _op + ", expecting " + OpOp3.COVARIANCE );
		
		ExecType et = optFindExecType();
		if ( et == ExecType.SPARK )  {
			// throw new HopsException("constructLopsCovariance for TertiaryOp not implemented for Spark");
			et = ExecType.CP;
		}
		if ( et == ExecType.MR ) {
			// combineTertiary -> CoVariance -> CastAsScalar
			CombineTernary combine = CombineTernary
					.constructCombineLop(
							CombineTernary.OperationTypes.PreCovWeighted,
							getInput().get(0).constructLops(),
							getInput().get(1).constructLops(),
							getInput().get(2).constructLops(),
							DataType.MATRIX, getValueType());

			combine.getOutputParameters().setDimensions(
					getInput().get(0).getDim1(),
					getInput().get(0).getDim2(),
					getInput().get(0).getRowsInBlock(),
					getInput().get(0).getColsInBlock(), 
					getInput().get(0).getNnz());

			CoVariance cov = new CoVariance(
					combine, DataType.MATRIX, getValueType(), et);

			cov.getOutputParameters().setDimensions(1, 1, 0, 0, -1);
			
			cov.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
			
			UnaryCP unary1 = new UnaryCP(
					cov, HopsOpOp1LopsUS.get(OpOp1.CAST_AS_SCALAR),
					getDataType(), getValueType());
			unary1.getOutputParameters().setDimensions(0, 0, 0, 0, -1);
			unary1.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
			setLops(unary1);
		}
		else {
			//System.out.println("COV Tertiary executing in CP...");
			CoVariance cov = new CoVariance(
					getInput().get(0).constructLops(), 
					getInput().get(1).constructLops(), 
					getInput().get(2).constructLops(), 
					getDataType(), getValueType(), et);
			cov.getOutputParameters().setDimensions(0, 0, 0, 0, -1);
			cov.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
			setLops(cov);
		}
	}
	
	/**
	 * Method to construct LOPs when op = QUANTILE | INTERQUANTILE.
	 * 
	 * @throws HopsException
	 * @throws LopsException
	 */
	private void constructLopsQuantile() throws HopsException, LopsException {
		
		if ( _op != OpOp3.QUANTILE && _op != OpOp3.INTERQUANTILE )
			throw new HopsException("Unexpected operation: " + _op + ", expecting " + OpOp3.QUANTILE + " or " + OpOp3.INTERQUANTILE );
		
		ExecType et = optFindExecType();
		
		if ( et == ExecType.SPARK )  {
			// throw new HopsException("constructLopsQuantile for TertiaryOp not implemented for Spark");
			et = ExecType.CP;
		}
		
		if ( et == ExecType.MR ) {
			CombineBinary combine = CombineBinary
					.constructCombineLop(
							OperationTypes.PreSort,
							getInput().get(0).constructLops(),
							getInput().get(1).constructLops(),
							DataType.MATRIX, getValueType());

			SortKeys sort = SortKeys
					.constructSortByValueLop(
							combine,
							SortKeys.OperationTypes.WithWeights,
							DataType.MATRIX, getValueType(), et);

			// If only a single quantile is computed, then "pick" operation executes in CP.
			ExecType et_pick = (getInput().get(2).getDataType() == DataType.SCALAR ? ExecType.CP : ExecType.MR);
			PickByCount pick = new PickByCount(
					sort,
					getInput().get(2).constructLops(),
					getDataType(),
					getValueType(),
					(_op == Hop.OpOp3.QUANTILE) ? PickByCount.OperationTypes.VALUEPICK
							: PickByCount.OperationTypes.RANGEPICK, et_pick, false);

			pick.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
			
			combine.getOutputParameters().setDimensions(
					getInput().get(0).getDim1(),
					getInput().get(0).getDim2(), 
					getInput().get(0).getRowsInBlock(), 
					getInput().get(0).getColsInBlock(),
					getInput().get(0).getNnz());
			sort.getOutputParameters().setDimensions(
					getInput().get(0).getDim1(),
					getInput().get(0).getDim2(), 
					getInput().get(0).getRowsInBlock(), 
					getInput().get(0).getColsInBlock(),
					getInput().get(0).getNnz());
			pick.getOutputParameters().setDimensions(getDim1(),
					getDim2(), getRowsInBlock(), getColsInBlock(), getNnz());

			setLops(pick);
		}
		else {
			SortKeys sort = SortKeys.constructSortByValueLop(
					getInput().get(0).constructLops(), 
					getInput().get(1).constructLops(), 
					SortKeys.OperationTypes.WithWeights, 
					getInput().get(0).getDataType(), getInput().get(0).getValueType(), et);
			PickByCount pick = new PickByCount(
					sort,
					getInput().get(2).constructLops(),
					getDataType(),
					getValueType(),
					(_op == Hop.OpOp3.QUANTILE) ? PickByCount.OperationTypes.VALUEPICK
							: PickByCount.OperationTypes.RANGEPICK, et, true);
			sort.getOutputParameters().setDimensions(
					getInput().get(0).getDim1(),
					getInput().get(0).getDim2(),
					getInput().get(0).getRowsInBlock(), 
					getInput().get(0).getColsInBlock(),
					getInput().get(0).getNnz());
			pick.getOutputParameters().setDimensions(getDim1(),
					getDim2(), getRowsInBlock(), getColsInBlock(), getNnz());

			setLops(pick);
		}
	}

	/**
	 * Method to construct LOPs when op = CTABLE.
	 * 
	 * @throws HopsException
	 * @throws LopsException
	 */
	private void constructLopsCtable() throws HopsException, LopsException {
		
		if ( _op != OpOp3.CTABLE )
			throw new HopsException("Unexpected operation: " + _op + ", expecting " + OpOp3.CTABLE );
		
		/*
		 * We must handle three different cases: case1 : all three
		 * inputs are vectors (e.g., F=ctable(A,B,W)) case2 : two
		 * vectors and one scalar (e.g., F=ctable(A,B)) case3 : one
		 * vector and two scalars (e.g., F=ctable(A))
		 */

		// identify the particular case
		
		// F=ctable(A,B,W)
		
		DataType dt1 = getInput().get(0).getDataType(); 
		DataType dt2 = getInput().get(1).getDataType(); 
		DataType dt3 = getInput().get(2).getDataType(); 
		Ternary.OperationTypes tertiaryOpOrig = Ternary.findCtableOperationByInputDataTypes(dt1, dt2, dt3);
 		
		// Compute lops for all inputs
		Lop[] inputLops = new Lop[getInput().size()];
		for(int i=0; i < getInput().size(); i++) {
			inputLops[i] = getInput().get(i).constructLops();
		}
		
		ExecType et = optFindExecType();
		if ( et == ExecType.SPARK )  {
			// throw new HopsException("constructLopsCtable for TertiaryOp not implemented for Spark");
			et = ExecType.CP;
		}
		
		if ( et == ExecType.CP ) 
		{	
			//for CP we support only ctable expand left
			Ternary.OperationTypes tertiaryOp = isSequenceRewriteApplicable(true) ? Ternary.OperationTypes.CTABLE_EXPAND_SCALAR_WEIGHT : tertiaryOpOrig;
			boolean ignoreZeros = false;
			
			if( isMatrixIgnoreZeroRewriteApplicable() ) { 
				ignoreZeros = true; //table - rmempty - rshape
				inputLops[0] = ((ParameterizedBuiltinOp)getInput().get(0)).getTargetHop().getInput().get(0).constructLops();
				inputLops[1] = ((ParameterizedBuiltinOp)getInput().get(1)).getTargetHop().getInput().get(0).constructLops();
			}
			
			Ternary tertiary = new Ternary(inputLops, tertiaryOp, getDataType(), getValueType(), ignoreZeros, et);
			
			tertiary.getOutputParameters().setDimensions(_dim1, _dim2, getRowsInBlock(), getColsInBlock(), -1);
			tertiary.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
			
			//force blocked output in CP (see below), otherwise binarycell
			tertiary.getOutputParameters().setDimensions(_dim1, _dim2, getRowsInBlock(), getColsInBlock(), -1);
			
			//tertiary opt, w/o reblock in CP
			setLops(tertiary);
		}
		else //MR
		{
			//for MR we support both ctable expand left and right
			Ternary.OperationTypes tertiaryOp = isSequenceRewriteApplicable() ? Ternary.OperationTypes.CTABLE_EXPAND_SCALAR_WEIGHT : tertiaryOpOrig;
			
			Group group1 = null, group2 = null, group3 = null, group4 = null;
			group1 = new Group(inputLops[0], Group.OperationTypes.Sort, getDataType(), getValueType());
			group1.getOutputParameters().setDimensions(getDim1(),
					getDim2(), getRowsInBlock(), getColsInBlock(), getNnz());
			
			group1.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());

			Ternary tertiary = null;
			// create "group" lops for MATRIX inputs
			switch (tertiaryOp) 
			{
				case CTABLE_TRANSFORM:
					// F = ctable(A,B,W)
					group2 = new Group(
							inputLops[1],
							Group.OperationTypes.Sort, getDataType(),
							getValueType());
					group2.getOutputParameters().setDimensions(getDim1(),
							getDim2(), getRowsInBlock(),
							getColsInBlock(), getNnz());
					group2.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
					
					group3 = new Group(
							inputLops[2],
							Group.OperationTypes.Sort, getDataType(),
							getValueType());
					group3.getOutputParameters().setDimensions(getDim1(),
							getDim2(), getRowsInBlock(),
							getColsInBlock(), getNnz());
					group3.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
					
					if ( inputLops.length == 3 )
						tertiary = new Ternary(
								new Lop[] {group1, group2, group3},
								tertiaryOp,
								getDataType(), getValueType(), et);	
					else 
						// output dimensions are given
						tertiary = new Ternary(
								new Lop[] {group1, group2, group3, inputLops[3], inputLops[4]},
								tertiaryOp,
								getDataType(), getValueType(), et);	
					break;
	
				case CTABLE_TRANSFORM_SCALAR_WEIGHT:
					// F = ctable(A,B) or F = ctable(A,B,1)
					group2 = new Group(
							inputLops[1],
							Group.OperationTypes.Sort, getDataType(),
							getValueType());
					group2.getOutputParameters().setDimensions(getDim1(),
							getDim2(), getRowsInBlock(),
							getColsInBlock(), getNnz());
					group2.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
					
					if ( inputLops.length == 3)
						tertiary = new Ternary(
								new Lop[] {group1,group2,inputLops[2]},
								tertiaryOp,
								getDataType(), getValueType(), et);
					else
						tertiary = new Ternary(
								new Lop[] {group1,group2,inputLops[2], inputLops[3], inputLops[4]},
								tertiaryOp,
								getDataType(), getValueType(), et);
						
					break;
			
				case CTABLE_EXPAND_SCALAR_WEIGHT:
					// F=ctable(seq(1,N),A) or F = ctable(seq,A,1)
					int left = isSequenceRewriteApplicable(true)?1:0; //left 1, right 0
					
					Group group = new Group(
							getInput().get(left).constructLops(),
							Group.OperationTypes.Sort, getDataType(),
							getValueType());
					group.getOutputParameters().setDimensions(getDim1(),
							getDim2(), getRowsInBlock(),
							getColsInBlock(), getNnz());
					//TODO remove group, whenever we push it into the map task
					
					if (inputLops.length == 3)
						tertiary = new Ternary(
								new Lop[] {					
										group, //matrix
										getInput().get(2).constructLops(), //weight
										new LiteralOp(String.valueOf(left),left).constructLops() //left
								},
								tertiaryOp,
								getDataType(), getValueType(), et);
					else
						tertiary = new Ternary(
								new Lop[] {					
										group,//getInput().get(1).constructLops(), //matrix
										getInput().get(2).constructLops(), //weight
										new LiteralOp(String.valueOf(left),left).constructLops(), //left
										inputLops[3],
										inputLops[4]
								},
								tertiaryOp,
								getDataType(), getValueType(), et);
					
					break;
					
				case CTABLE_TRANSFORM_HISTOGRAM:
					// F=ctable(A,1) or F = ctable(A,1,1)
					if ( inputLops.length == 3 )
						tertiary = new Ternary(
								new Lop[] {
										group1, 
										getInput().get(1).constructLops(),
										getInput().get(2).constructLops()
								},
								tertiaryOp,
								getDataType(), getValueType(), et);
					else
						tertiary = new Ternary(
								new Lop[] {
										group1, 
										getInput().get(1).constructLops(),
										getInput().get(2).constructLops(),
										inputLops[3],
										inputLops[4]
								},
								tertiaryOp,
								getDataType(), getValueType(), et);
						
					break;
				case CTABLE_TRANSFORM_WEIGHTED_HISTOGRAM:
					// F=ctable(A,1,W)
					group3 = new Group(
							getInput().get(2).constructLops(),
							Group.OperationTypes.Sort, getDataType(),
							getValueType());
					group3.getOutputParameters().setDimensions(getDim1(),
							getDim2(), getRowsInBlock(),
							getColsInBlock(), getNnz());
					group3.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
					
					if ( inputLops.length == 3)
						tertiary = new Ternary(
								new Lop[] {
										group1,
										getInput().get(1).constructLops(),
										group3},
								tertiaryOp,
								getDataType(), getValueType(), et);
					else
						tertiary = new Ternary(
								new Lop[] {
										group1,
										getInput().get(1).constructLops(),
										group3, inputLops[3], inputLops[4] },
								tertiaryOp,
								getDataType(), getValueType(), et);
						
					break;
				
				default:
					throw new HopsException("Invalid ternary operator type: "+_op);
			}

			// output dimensions are not known at compilation time
			tertiary.getOutputParameters().setDimensions(_dim1, _dim2, ( _dimInputsPresent ? getRowsInBlock() : -1), ( _dimInputsPresent ? getColsInBlock() : -1), -1);
			tertiary.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
			
			Lop lctable = tertiary;
			
			if( !_disjointInputs ) { //no need for aggregation if input indexed disjoint		
				
				group4 = new Group(
						tertiary, Group.OperationTypes.Sort, getDataType(),
						getValueType());
				group4.getOutputParameters().setDimensions(_dim1, _dim2, ( _dimInputsPresent ? getRowsInBlock() : -1), ( _dimInputsPresent ? getColsInBlock() : -1), -1);
				group4.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
	
				Aggregate agg1 = new Aggregate(
						group4, HopsAgg2Lops.get(AggOp.SUM), getDataType(),
						getValueType(), ExecType.MR);
				agg1.getOutputParameters().setDimensions(_dim1, _dim2, ( _dimInputsPresent ? getRowsInBlock() : -1), ( _dimInputsPresent ? getColsInBlock() : -1), -1);
	
				agg1.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());

				// kahamSum is used for aggregation but inputs do not have
				// correction values
				agg1.setupCorrectionLocation(CorrectionLocationType.NONE);
				lctable = agg1;
			}

			setLops( lctable );
			
			// In this case, output dimensions are known at the time of its execution, no need 
			// to introduce reblock lop since table itself outputs in blocked format if dims known.
			if ( !dimsKnown() && !_dimInputsPresent ) {
				setRequiresReblock( true );
			}
			
			// construct and set reblock lop as current root lop
			constructAndSetReblockLopIfRequired(et);
		}
	}
	
	@Override
	public String getOpString() {
		String s = new String("");
		s += "t(" + HopsOpOp3String.get(_op) + ")";
		return s;
	}

	public void printMe() throws HopsException {
		if (LOG.isDebugEnabled()){
			if (getVisited() != VisitStatus.DONE) {
				super.printMe();
				LOG.debug("  Operation: " + _op);
				for (Hop h : getInput()) {
					h.printMe();
				}
			}
			setVisited(VisitStatus.DONE);
		}
	}

	@Override
	public SQLLops constructSQLLOPs() throws HopsException {
		if ( _op == OpOp3.CTABLE ) {
			if (this.getInput().size() != 3)
				throw new HopsException("A tertiary Hop must have three inputs \n");

			GENERATES gen = determineGeneratesFlag();

			Hop hop1 = this.getInput().get(0);
			Hop hop2 = this.getInput().get(1);
			Hop hop3 = this.getInput().get(2);

			hop3.constructSQLLOPs();

			SQLLops maxsqllop = new SQLLops("", GENERATES.SQL, hop1
					.constructSQLLOPs(), hop2.constructSQLLOPs(),
					ValueType.DOUBLE, DataType.MATRIX);

			maxsqllop.set_properties(new SQLLopProperties());
			String resultName = "result_" + this.getName() + "_"
					+ this.getHopID();
			String maxName = "maximum_" + this.getName() + "_"
					+ this.getHopID();

			String qhop1name = SQLLops.addQuotes(hop1.getSqlLops()
					.get_tableName());
			String qhop2name = SQLLops.addQuotes(hop2.getSqlLops()
					.get_tableName());
			String qhop3name = SQLLops.addQuotes(hop3.getSqlLops()
					.get_tableName());

			String maxsql = String.format(SQLLops.MAXVAL2TABLES, qhop1name,
					qhop2name);
			maxsqllop.set_sql(maxsql);
			maxsqllop.set_tableName(maxName);
			maxsqllop.set_properties(new SQLLopProperties());
			SQLLopProperties maxprop = new SQLLopProperties();
			maxprop.setJoinType(JOINTYPE.NONE);
			maxprop.setAggType(AGGREGATIONTYPE.NONE);
			maxprop.setOpString("maxrow(" + hop1.getSqlLops().get_tableName()
					+ "),\r\n,maxcol(" + hop2.getSqlLops().get_tableName()
					+ ")");

			maxsqllop.set_properties(maxprop);

			SQLLops resultsqllop = new SQLLops("", GENERATES.SQL, hop1
					.constructSQLLOPs(), hop2.constructSQLLOPs(),
					ValueType.DOUBLE, DataType.MATRIX);

			String resultsql = String.format(SQLLops.CTABLE, qhop1name,
					qhop2name, qhop3name);
			resultsqllop.set_tableName(resultName);
			resultsqllop.set_sql(resultsql);

			SQLLopProperties resprop = new SQLLopProperties();
			resprop.setJoinType(JOINTYPE.TWO_INNERJOINS);
			resprop.setAggType(AGGREGATIONTYPE.SUM);
			resprop.setOpString("CTable(" + hop1.getSqlLops().get_tableName()
					+ ", " + hop2.getSqlLops().get_tableName() + ", "
					+ hop3.getSqlLops().get_tableName() + ")");

			resultsqllop.set_properties(resprop);

			SQLLops sqllop = new SQLLops(this.getName(), gen, resultsqllop,
					maxsqllop, this.getValueType(), this.getDataType());

			// TODO Uncomment this to make scalar placeholders
			if (this.getDataType() == DataType.SCALAR && gen == GENERATES.DML)
				sqllop.set_tableName("##" + sqllop.get_tableName() + "##");

			String qResultName = SQLLops.addQuotes(resultName);
			String qMaxName = SQLLops.addQuotes(maxName);
			sqllop.set_sql(String.format(SQLLops.ATTACHLASTZERO, qResultName,
					qMaxName, qResultName, qMaxName));

			SQLLopProperties zeroprop = new SQLLopProperties();
			zeroprop.setJoinType(JOINTYPE.NONE);
			zeroprop.setAggType(AGGREGATIONTYPE.NONE);
			zeroprop.setOpString("Last cell not empty");

			this.setSqlLops(sqllop);

			return sqllop;
		}
		return null;
	}

	@SuppressWarnings("unused")
	private ISQLSelect getCTableSelect(String name1, String name2, String name3) {
		SQLSelectStatement stmt = new SQLSelectStatement();
		stmt.getColumns().add("alias_a.value as row");
		stmt.getColumns().add("alias_b.value as col");
		stmt.getColumns().add("sum(alias_c.value) as value");

		SQLJoin j1 = new SQLJoin();
		j1.setJoinType(JOINTYPE.INNERJOIN);
		j1.setTable1(new SQLTableReference(name1));

		SQLJoin j2 = new SQLJoin();
		j2.setJoinType(JOINTYPE.INNERJOIN);
		j2.setTable1(new SQLTableReference(name2));
		j2.setTable2(new SQLTableReference(name3));
		j2.getConditions().add(new SQLCondition("alias_a.row = alias_b.row"));
		j2.getConditions().add(
				new SQLCondition(BOOLOP.AND, "alias_a.col = alias_b.col"));
		j1.setTable2(j2);
		j1.getConditions().add(new SQLCondition("alias_c.row = alias_a.row"));
		j1.getConditions().add(
				new SQLCondition(BOOLOP.AND, "alias_c.col = alias_a.col"));

		stmt.setTable(j1);
		stmt.getGroupBys().add("alias_a.value");
		stmt.getGroupBys().add("alias_b.value");
		return stmt;
	}

	@SuppressWarnings("unused")
	private ISQLSelect getMaxrow2TablesSelect(String name1, String name2) {
		SQLSelectStatement stmt = new SQLSelectStatement();
		stmt.getColumns().add("max(alias_a.value) AS mrow");
		stmt.getColumns().add("max(alias_b.value) AS mcol");

		SQLJoin crossJoin = new SQLJoin();
		crossJoin.setJoinType(JOINTYPE.CROSSJOIN);
		crossJoin.setTable1(new SQLTableReference(name1, SQLLops.ALIAS_A));
		crossJoin.setTable2(new SQLTableReference(name2, SQLLops.ALIAS_B));
		stmt.setTable(crossJoin);

		return stmt;
	}

	@SuppressWarnings("unused")
	private ISQLSelect getAttachLastZeroSelect(String res, String max) {
		SQLUnion union = new SQLUnion();
		SQLSelectStatement stmt1 = new SQLSelectStatement();
		SQLSelectStatement stmt2 = new SQLSelectStatement();

		stmt1.getColumns().add("*");
		stmt1.setTable(new SQLTableReference(res));

		stmt2.getColumns().add("mrow AS row");
		stmt2.getColumns().add("mcol AS col");
		stmt2.getColumns().add("0 AS value");

		stmt2.setTable(new SQLTableReference(max));

		SQLSelectStatement tmp = new SQLSelectStatement();
		SQLJoin join = new SQLJoin();
		join.setJoinType(JOINTYPE.INNERJOIN);
		join.setTable1(new SQLTableReference(res));
		join.setTable2(new SQLTableReference(max));
		join.getConditions().add(new SQLCondition("mrow = row"));
		join.getConditions().add(new SQLCondition(BOOLOP.AND, "mcol = col"));
		tmp.setTable(join);
		tmp.getColumns().add("*");

		stmt2.getWheres().add(
				new SQLCondition("NOT EXISTS (" + tmp.toString() + ")"));
		union.setSelect1(stmt1);
		union.setSelect2(stmt2);
		union.setUnionType(UNIONTYPE.UNIONALL);
		return union;
	}

	@Override
	public boolean allowsAllExecTypes()
	{
		return true;
	}

	@Override
	protected double computeOutputMemEstimate( long dim1, long dim2, long nnz )
	{
		//only quantile and ctable produce matrices
		
		switch( _op ) 
		{
			case CTABLE:
				// since the dimensions of both inputs must be the same, checking for one input is sufficient
				//   worst case dimensions of C = [m,m]
				//   worst case #nnz in C = m => sparsity = 1/m
				// for ctable_histogram also one dimension is known
				double sparsity = OptimizerUtils.getSparsity(dim1, dim2, (nnz<=dim1)?nnz:dim1); 
				return OptimizerUtils.estimateSizeExactSparsity(dim1, dim2, sparsity);
				
			case QUANTILE:
				// This part of the code is executed only when a vector of quantiles are computed
				// Output is a vector of length = #of quantiles to be computed, and it is likely to be dense.
				return OptimizerUtils.estimateSizeExactSparsity(dim1, dim2, 1.0);
				
			default:
				throw new RuntimeException("Memory for operation (" + _op + ") can not be estimated.");
		}
	}
	
	@Override
	protected double computeIntermediateMemEstimate( long dim1, long dim2, long nnz )
	{
		double ret = 0;
		if( _op == OpOp3.CTABLE ) {
			if ( _dim1 >0 && _dim2 > 0 ) {
				// output dimensions are known, and hence a MatrixBlock is allocated
				// Allocated block is in sparse format only when #inputRows < #cellsInOutput
				long inRows = getInput().get(0).getDim1();
				//long outputNNZ = Math.min(inRows, _dim1*_dim2);
				boolean sparse = (inRows > 0 && inRows < _dim1*_dim2);
				ret = OptimizerUtils.estimateSizeExactSparsity(_dim1, _dim2, (sparse ? 0.1d : 1.0d) );
			}
			else {
				ret =  2*4 * dim1 + //hash table (worst-case overhead 2x)
						  32 * dim1; //values: 2xint,1xObject
			}
		}
		else if ( _op == OpOp3.QUANTILE ) {
			// buffer (=2*input_size) and output (=2*input_size) for SORT operation
			// getMemEstimate works for both cases of known dims and worst-case stats
			ret = getInput().get(0).getMemEstimate() * 4;  
		}
		
		return ret;
	}
	
	@Override
	protected long[] inferOutputCharacteristics( MemoTable memo )
	{
		long[] ret = null;
	
		MatrixCharacteristics[] mc = memo.getAllInputStats(getInput());
		
		switch( _op ) 
		{
			case CTABLE:
				long worstCaseDim = -1;
				boolean inferred = false;
				// since the dimensions of both inputs must be the same, checking for one input is sufficient
				if( mc[0].dimsKnown() || mc[1].dimsKnown() ) {
					// Output dimensions are completely data dependent. In the worst case, 
					// #categories in each attribute = #rows (e.g., an ID column, say EmployeeID).
					// both inputs are one-dimensional matrices with exact same dimensions, m = size of longer dimension
					worstCaseDim = (mc[0].dimsKnown())
					          ? (mc[0].getRows() > 1 ? mc[0].getRows() : mc[0].getCols() )
							  : (mc[1].getRows() > 1 ? mc[1].getRows() : mc[1].getCols() );
					//note: for ctable histogram dim2 known but automatically replaces m         
					//ret = new long[]{m, m, m};
				}
				if ( this.getInput().size() > 3 && this.getInput().get(3).getKind() == Kind.LiteralOp && this.getInput().get(4).getKind() == Kind.LiteralOp ) {
					try {
						long outputDim1 = ((LiteralOp)getInput().get(3)).getLongValue();
						long outputDim2 = ((LiteralOp)getInput().get(4)).getLongValue();
						long outputNNZ = ( outputDim1*outputDim2 > outputDim1 ? outputDim1 : outputDim1*outputDim2 );
						
						this._dim1 = outputDim1;
						this._dim2 = outputDim2;
						inferred = true;
						return new long[]{outputDim1, outputDim2, outputNNZ};
					} catch (HopsException e) {
						throw new RuntimeException(e);
					}
				}
				if ( !inferred ) {
					//note: for ctable histogram dim2 known but automatically replaces m         
					return new long[]{worstCaseDim, worstCaseDim, worstCaseDim};
				}
				break;
			
			case QUANTILE:
				if( mc[2].dimsKnown() )
					return new long[]{mc[2].getRows(), 1, mc[2].getRows()};
				break;
			
			default:
				throw new RuntimeException("Memory for operation (" + _op + ") can not be estimated.");
		}
				
		return ret;
	}
	

	@Override
	protected ExecType optFindExecType() 
		throws HopsException 
	{	
		checkAndSetForcedPlatform();
		
		if( _etypeForced != null ) 			
		{
			_etype = _etypeForced;
		}
		else
		{	
			if ( OptimizerUtils.isMemoryBasedOptLevel() ) {
				_etype = findExecTypeByMemEstimate();
			}
			else if ( (getInput().get(0).areDimsBelowThreshold() 
					&& getInput().get(1).areDimsBelowThreshold()
					&& getInput().get(2).areDimsBelowThreshold()) 
					//|| (getInput().get(0).isVector() && getInput().get(1).isVector() && getInput().get(1).isVector() )
				)
				_etype = ExecType.CP;
			else
				_etype = ExecType.MR;
			
			//check for valid CP dimensions and matrix size
			checkAndSetInvalidCPDimsAndSize();
			
			//mark for recompile (forever)
			// Necessary condition for recompilation is unknown dimensions.
			// When execType=CP, it is marked for recompilation only when additional
			// dimension inputs are provided (and those values are unknown at initial compile time).
			if( OptimizerUtils.ALLOW_DYN_RECOMPILATION && !dimsKnown(true) ) {
				if ( _etype==ExecType.MR || (_etype == ExecType.CP && _dimInputsPresent))
					setRequiresRecompile();
			}
		}
		
		return _etype;
	}
	
	@Override
	public void refreshSizeInformation()
	{
		if ( getDataType() == DataType.SCALAR ) 
		{
			//do nothing always known
		}
		else 
		{
			switch( _op ) 
			{
				case CTABLE:
					//in general, do nothing because the output size is data dependent
					Hop input1 = getInput().get(0);
					Hop input2 = getInput().get(1);
					Hop input3 = getInput().get(2);
					
					
					if ( _dim1 == -1 || _dim2 == -1 ) { 
						//for ctable_expand at least one dimension is known
						if( isSequenceRewriteApplicable() )
						{
							if( input1 instanceof DataGenOp && ((DataGenOp)input1).getOp()==DataGenMethod.SEQ )
								setDim1( input1._dim1 );
							else //if( input2 instanceof DataGenOp && ((DataGenOp)input2).getDataGenMethod()==DataGenMethod.SEQ )
								setDim2( input2._dim1 );
						}
						//for ctable_histogram also one dimension is known
						Ternary.OperationTypes tertiaryOp = Ternary.findCtableOperationByInputDataTypes(
																input1.getDataType(), input2.getDataType(), input3.getDataType());
						if(  tertiaryOp==Ternary.OperationTypes.CTABLE_TRANSFORM_HISTOGRAM
							&& input2 instanceof LiteralOp )
						{
							setDim2( HopRewriteUtils.getIntValueSafe((LiteralOp)input2) );
						}
						
						// if output dimensions are provided, update _dim1 and _dim2
						if( getInput().size() >= 5 ) {
							if( getInput().get(3).getKind() == Kind.LiteralOp )
								setDim1( HopRewriteUtils.getIntValueSafe((LiteralOp)getInput().get(3)) );
							if( getInput().get(4).getKind() == Kind.LiteralOp )
								setDim2( HopRewriteUtils.getIntValueSafe((LiteralOp)getInput().get(4)) );
						}
					}

					break;
				
				case QUANTILE:
					// This part of the code is executed only when a vector of quantiles are computed
					// Output is a vector of length = #of quantiles to be computed, and it is likely to be dense.
					// TODO qx1
					break;	
					
				default:
					throw new RuntimeException("Size information for operation (" + _op + ") can not be updated.");
			}
		}	
	}
	
	@Override
	public Object clone() throws CloneNotSupportedException 
	{
		TernaryOp ret = new TernaryOp();	
		
		//copy generic attributes
		ret.clone(this, false);
		
		//copy specific attributes
		ret._op = _op;
		ret._dimInputsPresent  = _dimInputsPresent;
		ret._disjointInputs    = _disjointInputs;
		
		return ret;
	}
	
	@Override
	public boolean compare( Hop that )
	{
		if( that._kind!=Kind.TernaryOp )
			return false;
		
		TernaryOp that2 = (TernaryOp)that;
		
		//compare basic inputs and weights (always existing)
		boolean ret = (_op == that2._op
				&& getInput().get(0) == that2.getInput().get(0)
				&& getInput().get(1) == that2.getInput().get(1)
				&& getInput().get(2) == that2.getInput().get(2));
		
		//compare optional dimension parameters
		ret &= (_dimInputsPresent == that2._dimInputsPresent);
		if( ret && _dimInputsPresent ){
			ret &= getInput().get(3) == that2.getInput().get(3)
				&& getInput().get(4) == that2.getInput().get(4);
		}
		
		//compare optimizer hints and parameters
		ret &= _disjointInputs == that2._disjointInputs
			&& _outputEmptyBlocks == that2._outputEmptyBlocks;
		
		return ret;
	}
	
	/**
	 * 
	 * @return
	 */
	private boolean isSequenceRewriteApplicable() 
	{
		return    isSequenceRewriteApplicable(true)
			   || isSequenceRewriteApplicable(false);
	}
	
	/**
	 * 
	 * @param left
	 * @return
	 */
	private boolean isSequenceRewriteApplicable( boolean left ) 
	{
		boolean ret = false;
		
		//early abort if rewrite globally not allowed
		if( !ALLOW_CTABLE_SEQUENCE_REWRITES )
			return ret;
		
		try
		{
			if( getInput().size()==2 || (getInput().size()==3 && getInput().get(2).getDataType()==DataType.SCALAR) )
			{
				Hop input1 = getInput().get(0);
				Hop input2 = getInput().get(1);
				if( input1.getDataType() == DataType.MATRIX && input2.getDataType() == DataType.MATRIX )
				{
					//probe rewrite on left input
					if( left && input1 instanceof DataGenOp )
					{
						DataGenOp dgop = (DataGenOp) input1;
						if( dgop.getOp() == DataGenMethod.SEQ ){
							Hop incr = dgop.getInput().get(dgop.getParamIndex(Statement.SEQ_INCR));
							ret = (incr instanceof LiteralOp && HopRewriteUtils.getIntValue((LiteralOp)incr)==1)
								  || dgop.getIncrementValue()==1.0; //set by recompiler
						}
					}
					//probe rewrite on right input
					if( !left && input2 instanceof DataGenOp )
					{
						DataGenOp dgop = (DataGenOp) input2;
						if( dgop.getOp() == DataGenMethod.SEQ ){
							Hop incr = dgop.getInput().get(dgop.getParamIndex(Statement.SEQ_INCR));
							ret |= (incr instanceof LiteralOp && HopRewriteUtils.getIntValue((LiteralOp)incr)==1)
								   || dgop.getIncrementValue()==1.0; //set by recompiler;
						}
					}
				}			
			}
		}
		catch(Exception ex)
		{
			throw new RuntimeException(ex);
			//ret = false;
		}
			
		return ret;
	}
	
	/**
	 * Used for (1) constructing CP lops (hop-lop rewrite), and (2) in order to determine
	 * if dag split after removeEmpty necessary (#2 is precondition for #1). 
	 * 
	 * @return
	 */
	public boolean isMatrixIgnoreZeroRewriteApplicable() 
	{
		boolean ret = false;
		
		//early abort if rewrite globally not allowed
		if( !ALLOW_CTABLE_SEQUENCE_REWRITES || _op!=OpOp3.CTABLE )
			return ret;
		
		try
		{
			//1) check for ctable CTABLE_TRANSFORM_SCALAR_WEIGHT
			if( getInput().size()==2 || (getInput().size()>2 && getInput().get(2).getDataType()==DataType.SCALAR) )
			{
				Hop input1 = getInput().get(0);
				Hop input2 = getInput().get(1);
				//2) check for remove empty pair 
				if( input1.getDataType() == DataType.MATRIX && input2.getDataType() == DataType.MATRIX 
					&& input1 instanceof ParameterizedBuiltinOp && ((ParameterizedBuiltinOp)input1).getOp()==ParamBuiltinOp.RMEMPTY
					&& input2 instanceof ParameterizedBuiltinOp && ((ParameterizedBuiltinOp)input2).getOp()==ParamBuiltinOp.RMEMPTY )
				{
					ParameterizedBuiltinOp pb1 = (ParameterizedBuiltinOp)input1;
					ParameterizedBuiltinOp pb2 = (ParameterizedBuiltinOp)input2;
					Hop pbin1 = pb1.getTargetHop();
					Hop pbin2 = pb2.getTargetHop();
					
					//3) check for reshape pair
					if(    pbin1 instanceof ReorgOp && ((ReorgOp)pbin1).getOp()==ReOrgOp.RESHAPE
						&& pbin2 instanceof ReorgOp && ((ReorgOp)pbin2).getOp()==ReOrgOp.RESHAPE )
					{
						//4) check common non-zero input (this allows to infer two things: 
						//(a) that the dims are equivalent, and zero values for remove empty are aligned)
						Hop left = pbin1.getInput().get(0);
						Hop right = pbin2.getInput().get(0);
						if(    left instanceof BinaryOp && ((BinaryOp)left).getOp()==OpOp2.MULT
							&& left.getInput().get(0) instanceof BinaryOp && ((BinaryOp)left.getInput().get(0)).getOp()==OpOp2.NOTEQUAL
							&& left.getInput().get(0).getInput().get(1) instanceof LiteralOp && HopRewriteUtils.getDoubleValue((LiteralOp)left.getInput().get(0).getInput().get(1))==0 
							&& left.getInput().get(0).getInput().get(0) == right ) //relies on CSE
						{	
							ret = true;
						}
						else if(    right instanceof BinaryOp && ((BinaryOp)right).getOp()==OpOp2.MULT
							&& right.getInput().get(0) instanceof BinaryOp && ((BinaryOp)right.getInput().get(0)).getOp()==OpOp2.NOTEQUAL
							&& right.getInput().get(0).getInput().get(1) instanceof LiteralOp && HopRewriteUtils.getDoubleValue((LiteralOp)right.getInput().get(0).getInput().get(1))==0 
							&& right.getInput().get(0).getInput().get(0) == left ) //relies on CSE
						{
							ret = true;
						}
					}
				}			
			}
		}
		catch(Exception ex)
		{
			throw new RuntimeException(ex);
			//ret = false;
		}
		
		return ret;
	}
}